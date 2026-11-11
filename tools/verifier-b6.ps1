# Verification B6 : propagation du traceId et passthrough de Idempotency-Key.
# Gateway, faux services ET Redis doivent tourner.
#   powershell -ExecutionPolicy Bypass -File tools\verifier-b6.ps1
# Ce script fait 1 login : si tu viens de lancer verifier-b5.ps1, attends ~1 minute (seau "login" vide -> 429).
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

# Envoie une requete et renvoie le corps JSON decode ET le texte des en-tetes de la reponse
function Appel([string]$url, [string]$method = "GET", [string]$token = $null, [string[]]$headers = @()) {
    $tmp = [IO.Path]::GetTempFileName()
    $curlArgs = @("-s", "-D", $tmp, "-X", $method)
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    foreach ($h in $headers) { $curlArgs += @("-H", $h) }
    $curlArgs += $url
    $texte = (& curl.exe @curlArgs) -join ""
    $entetes = Get-Content $tmp -Raw
    Remove-Item $tmp -ErrorAction SilentlyContinue
    $corps = $null
    if (-not [string]::IsNullOrWhiteSpace($texte)) { $corps = $texte | ConvertFrom-Json }
    return [pscustomobject]@{ Corps = $corps; Entetes = $entetes }
}
# Valeur de X-Trace-Id dans les en-tetes de la reponse (null si absent)
function TraceDeLaReponse($entetes) {
    if ($entetes -match "(?im)^X-Trace-Id:\s*(\S+)") { return $Matches[1] }
    return $null
}

Write-Host "`n=== Verification B6 (traceId + Idempotency-Key) : $Gateway ===`n"

$login = Appel "$Gateway/api/auth/login" "POST"
$token = $login.Corps.accessToken
Check "Token obtenu (login)"                                  "oui" (Oui $token)

Write-Host "`n--- Idempotency-Key : transmise intacte jusqu'a Payment ---`n"
$cle = "9f1c2b7e-3a44-4d0e-8b19-6f2a5c7d1e30"
$r1 = Appel "$Gateway/api/payments/transfer" "POST" $token @("Idempotency-Key: $cle", "Content-Type: application/json")
Check "Payment recoit la cle exacte"                          $cle $r1.Corps.headers.'Idempotency-Key'
$r2 = Appel "$Gateway/api/payments/transfer" "POST" $token @("Idempotency-Key: $cle", "Content-Type: application/json")
Check "Rejeu avec la meme cle : transmis a nouveau, intact"   $cle $r2.Corps.headers.'Idempotency-Key'
$r3 = Appel "$Gateway/api/payments/transfer" "POST" $token @("Content-Type: application/json")
Check "Sans cle : le gateway n'en invente pas"                "aucune" $(if ($r3.Corps.headers.'Idempotency-Key') { $r3.Corps.headers.'Idempotency-Key' } else { "aucune" })

Write-Host "`n--- TraceId : un seul identifiant de bout en bout ---`n"
$sans = Appel "$Gateway/api/accounts/42/balance" "GET" $token
$vuParService = $sans.Corps.headers.'X-Trace-Id'
Check "Sans X-Trace-Id client : un UUID est genere (36 car.)"  "36" ("" + ($vuParService).Length)
Check "Le client recoit le meme identifiant que le service"   $vuParService (TraceDeLaReponse $sans.Entetes)

$choisi = "demo-b6-0001"
$avec = Appel "$Gateway/api/accounts/42/balance" "GET" $token @("X-Trace-Id: $choisi")
Check "X-Trace-Id client valide : repris par le service"      $choisi $avec.Corps.headers.'X-Trace-Id'
Check "X-Trace-Id client valide : repris dans la reponse"     $choisi (TraceDeLaReponse $avec.Entetes)

$dangereux = Appel "$Gateway/api/accounts/42/balance" "GET" $token @("X-Trace-Id: x y;DROP")
Check "X-Trace-Id dangereux : remplace par un UUID"           "36" ("" + ($dangereux.Corps.headers.'X-Trace-Id').Length)

$erreur = Appel "$Gateway/api/accounts/42/balance" "GET"
Check "Erreur 401 : traceId du corps = en-tete X-Trace-Id"    (TraceDeLaReponse $erreur.Entetes) $erreur.Corps.traceId
$nbEntetes = ([regex]::Matches($erreur.Entetes, "(?im)^X-Trace-Id:")).Count
Check "Un seul en-tete X-Trace-Id dans la reponse"            "1" ("" + $nbEntetes)

Write-Host ""
if ($script:echecs -eq 0) { Write-Host "Tous les controles B6 sont passes." -ForegroundColor Green }
else { Write-Host "$($script:echecs) controle(s) en echec." -ForegroundColor Red }

Write-Host "`nVERIFICATION MANUELLE : dans la console du gateway, cherche la ligne JSON qui contient"
Write-Host "  $choisi"
Write-Host "Elle doit montrer method, path, status, durationMs, clientIp, route. Fais-en une capture.`n"
exit $script:echecs
