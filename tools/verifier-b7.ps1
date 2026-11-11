# Verification B7 : timeouts, circuit breaker, 503 structure, erreurs metier intactes.
# Gateway (redemarre apres B7), faux services ET Redis doivent tourner. Duree : environ 40 secondes.
#   powershell -ExecutionPolicy Bypass -File tools\verifier-b7.ps1
# Ce script fait 1 login : attends ~1 minute apres verifier.ps1 ou verifier-b5.ps1 (quota login par minute).
param([string]$Gateway = "http://localhost:8080")

$script:echecs = 0

function Check([string]$nom, [string]$attendu, [string]$obtenu) {
    if ($attendu -eq $obtenu) {
        Write-Host ("PASS  {0,-62} attendu {1}, obtenu {2}" -f $nom, $attendu, $obtenu) -ForegroundColor Green
    } else {
        Write-Host ("FAIL  {0,-62} attendu {1}, obtenu {2}" -f $nom, $attendu, $obtenu) -ForegroundColor Red
        $script:echecs++
    }
}
function Oui($condition) { if ($condition) { return "oui" } else { return "non" } }

# Envoie une requete ; renvoie le code HTTP, la duree en secondes, le corps JSON et les en-tetes
function Appel([string]$url, [string]$method = "GET", [string]$token = $null) {
    $tmpH = [IO.Path]::GetTempFileName()
    $curlArgs = @("-s", "-D", $tmpH, "-X", $method, "-w", "`n%{http_code} %{time_total}")
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    $curlArgs += $url
    $sortie = (& curl.exe @curlArgs) -join "`n"
    $entetes = Get-Content $tmpH -Raw
    Remove-Item $tmpH -ErrorAction SilentlyContinue
    $lignes = $sortie -split "`n"
    $fin = $lignes[-1] -split " "
    $texte = ($lignes[0..($lignes.Length - 2)] -join "").Trim()
    $corps = $null
    if ($texte) { try { $corps = $texte | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{
        Code = $fin[0]; Secondes = [double]::Parse($fin[1], [Globalization.CultureInfo]::InvariantCulture)
        Corps = $corps; Entetes = $entetes }
}

Write-Host "`n=== Verification B7 (resilience) : $Gateway ===`n"

$login = Appel "$Gateway/api/auth/login" "POST"
$token = $login.Corps.accessToken
Check "Token obtenu (login)" "oui" (Oui $token)

Write-Host "`n--- Cas normaux : le circuit ne gene pas ---`n"
$ok = Appel "$Gateway/api/accounts/42/balance" "GET" $token
Check "Appel normal vers banking" "200" $ok.Code

$metier = Appel "$Gateway/api/payments/conflict" "GET" $token
Check "Erreur metier 409 : traverse le gateway" "409" $metier.Code
Check "Erreur metier : code du service conserve" "INSUFFICIENT_FUNDS" $metier.Corps.code

Write-Host "`n--- Service en panne (503) : reponse structuree du gateway ---`n"
$panne = Appel "$Gateway/api/payments/boom" "GET" $token
Check "Service repond 503 : le client recoit 503" "503" $panne.Code
Check "Corps : code SERVICE_UNAVAILABLE (pas celui du service)" "SERVICE_UNAVAILABLE" $panne.Corps.code
Check "Corps : traceId present" "oui" (Oui $panne.Corps.traceId)
$trace = if ($panne.Entetes -match "(?im)^X-Trace-Id:\s*(\S+)") { $Matches[1] } else { "" }
Check "traceId du corps = en-tete X-Trace-Id" $trace $panne.Corps.traceId

Write-Host "`n--- Timeout : service trop lent (patience, environ 3 s) ---`n"
$lent = Appel "$Gateway/api/payments/slow" "GET" $token
Check "Service trop lent : 503" "503" $lent.Code
Check "Coupe vers 3 s (entre 2,5 et 4,5 s)" "oui" (Oui ($lent.Secondes -gt 2.5 -and $lent.Secondes -lt 4.5))
Write-Host ("      duree mesuree : {0} s" -f $lent.Secondes)

Write-Host "`n--- Ouverture du circuit payment (8 pannes rapides) ---`n"
1..8 | ForEach-Object { Appel "$Gateway/api/payments/boom" "GET" $token | Out-Null }
$rapide = Appel "$Gateway/api/payments/slow" "GET" $token
Check "Circuit ouvert : 503" "503" $rapide.Code
Check "Circuit ouvert : reponse immediate (< 1 s au lieu de 3 s)" "oui" (Oui ($rapide.Secondes -lt 1))
Write-Host ("      duree mesuree : {0} s" -f $rapide.Secondes)
$sainPaye = Appel "$Gateway/api/payments/conflict" "GET" $token
Check "Circuit ouvert : meme un appel sain est refuse (fail-fast)" "503" $sainPaye.Code

Write-Host "`n--- Isolation : un circuit par service ---`n"
$autre = Appel "$Gateway/api/accounts/42/balance" "GET" $token
Check "banking reste disponible pendant que payment est ouvert" "200" $autre.Code
$notif = Appel "$Gateway/api/notifications/42" "GET" $token
Check "notification reste disponible" "200" $notif.Code

Write-Host "`n--- Recuperation : attente de la fermeture (12 s) ---`n"
Start-Sleep -Seconds 12
$essais = 1..3 | ForEach-Object { (Appel "$Gateway/api/payments/42/status" "GET" $token).Code }
Check "Apres l'attente : 3 appels d'essai reussissent" "200 200 200" ($essais -join " ")
$apres = Appel "$Gateway/api/payments/42/status" "GET" $token
Check "Circuit referme : service de nouveau normal" "200" $apres.Code

Write-Host ""
if ($script:echecs -eq 0) { Write-Host "Tous les controles B7 sont passes." -ForegroundColor Green }
else { Write-Host "$($script:echecs) controle(s) en echec." -ForegroundColor Red }
exit $script:echecs
