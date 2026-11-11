# Verification B5 : CORS + rate limiting. Gateway, faux services ET Redis doivent tourner.
#   powershell -ExecutionPolicy Bypass -File tools\verifier-b5.ps1
# Attention : ce script vide volontairement le seau "login" de ton IP. Attends ~1 minute avant de relancer
# verifier.ps1 (sinon le login de verifier.ps1 peut recevoir 429).
param([string]$Gateway = "http://localhost:8080", [string]$Front = "http://localhost:3000")

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

# Reponse complete (en-tetes seulement) sous forme de texte
function Entetes([string]$url, [string]$method, [string[]]$headers = @()) {
    $curlArgs = @("-s", "-D", "-", "-o", "NUL", "-X", $method)
    foreach ($h in $headers) { $curlArgs += @("-H", $h) }
    $curlArgs += $url
    return ((& curl.exe @curlArgs) -join "`n")
}
function Code([string]$url, [string]$method, [string[]]$headers = @()) {
    $curlArgs = @("-s", "-o", "NUL", "-w", "%{http_code}", "-X", $method)
    foreach ($h in $headers) { $curlArgs += @("-H", $h) }
    $curlArgs += $url
    return (& curl.exe @curlArgs)
}

Write-Host "`n=== Verification B5 (CORS + rate limiting) : $Gateway ===`n"
Write-Host "--- CORS ---`n"

$pre = @("Origin: $Front", "Access-Control-Request-Method: POST", "Access-Control-Request-Headers: authorization,idempotency-key,content-type")
Check "Pre-verification du frontend (sans token)"            "200" (Code "$Gateway/api/payments/transfer" "OPTIONS" $pre)
$h = Entetes "$Gateway/api/payments/transfer" "OPTIONS" $pre
Check "  Allow-Origin = origine du frontend"                  "oui" (Oui ($h -match "(?i)Access-Control-Allow-Origin: $([regex]::Escape($Front))"))
Check "  Idempotency-Key autorise"                            "oui" (Oui ($h -match "(?i)Access-Control-Allow-Headers:.*Idempotency-Key"))
Check "  Allow-Credentials = true"                            "oui" (Oui ($h -match "(?i)Access-Control-Allow-Credentials: true"))

$evil = @("Origin: http://evil.example", "Access-Control-Request-Method: POST")
Check "Pre-verification depuis une origine inconnue"          "403" (Code "$Gateway/api/payments/transfer" "OPTIONS" $evil)
$h = Entetes "$Gateway/api/payments/transfer" "OPTIONS" $evil
Check "  Aucun Allow-Origin pour l'origine inconnue"          "non" (Oui ($h -match "(?i)Access-Control-Allow-Origin"))

# Une reponse 401 doit porter les en-tetes CORS, sinon le navigateur la cache au JavaScript du frontend
$h = Entetes "$Gateway/api/accounts/42/balance" "GET" @("Origin: $Front")
Check "Reponse 401 avec en-tetes CORS"                        "oui" (Oui (($h -match "HTTP/1.1 401") -and ($h -match "(?i)Access-Control-Allow-Origin: $([regex]::Escape($Front))")))
Check "  X-Trace-Id lisible par le frontend (Expose-Headers)" "oui" (Oui ($h -match "(?i)Access-Control-Expose-Headers:.*X-Trace-Id"))

Write-Host "`n--- Rate limiting (login) ---`n"
Write-Host "Envoi de rafales de POST /api/auth/login (maximum 30)...`n"

$nb200 = 0; $nb429 = 0; $premier429 = 0
for ($i = 1; $i -le 30; $i++) {
    $c = Code "$Gateway/api/auth/login" "POST"
    if ($c -eq "200") { $nb200++ }
    if ($c -eq "429") { $nb429++; if ($premier429 -eq 0) { $premier429 = $i }; break }
}
Check "Au moins une requete bloquee (429)"                    "oui" (Oui ($nb429 -ge 1))
Write-Host ("      ({0} reponses 200 avant le premier 429, rang {1})" -f $nb200, $premier429)

# Juste apres : le seau est vide, la reponse suivante doit etre un 429 complet
$h = Entetes "$Gateway/api/auth/login" "POST" @("Origin: $Front")
Check "429 : statut"                                          "oui" (Oui ($h -match "HTTP/1.1 429"))
Check "429 : en-tete Retry-After"                             "oui" (Oui ($h -match "(?i)Retry-After: \d+"))
Check "429 : en-tete X-Trace-Id"                              "oui" (Oui ($h -match "(?i)X-Trace-Id: "))
Check "429 : en-tetes CORS presents"                          "oui" (Oui ($h -match "(?i)Access-Control-Allow-Origin: "))
$corps = (& curl.exe -s -X POST "$Gateway/api/auth/login") -join ""
if ($corps) { $json = $corps | ConvertFrom-Json } else { $json = $null }
Check "429 : corps JSON, champ code = TOO_MANY_REQUESTS"      "TOO_MANY_REQUESTS" $json.code
Check "429 : corps JSON, traceId present"                     "oui" (Oui ($json.traceId))

# Le seau "login" est independant des autres routes
Check "Sante du gateway jamais limitee"                       "200" (Code "$Gateway/actuator/health" "GET")
Check "Inscription : seau separe de celui du login"           "200" (Code "$Gateway/api/users/register" "POST")

Write-Host ""
if ($script:echecs -eq 0) { Write-Host "Tous les controles B5 sont passes." -ForegroundColor Green }
else { Write-Host "$($script:echecs) controle(s) en echec." -ForegroundColor Red }
exit $script:echecs
