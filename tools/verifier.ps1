# Verification de bout en bout du gateway (B3 + B4) : a lancer quand les faux services, le gateway ET Redis tournent.
#   powershell -ExecutionPolicy Bypass -File tools\verifier.ps1
# Chaque ligne affiche PASS ou FAIL : une seule execution donne toutes les captures de fonctionnement.
param([string]$Gateway = "http://localhost:8080")

$script:echecs = 0

# Renvoie uniquement le code HTTP d'une requete (corps ignore)
function Code([string]$url, [string]$method = "GET", [string]$token = $null) {
    $curlArgs = @("-s", "-o", "NUL", "-w", "%{http_code}", "-X", $method)
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    $curlArgs += $url
    return (& curl.exe @curlArgs)
}

# Demande un token au (faux) Identity, a travers le gateway : teste aussi la route publique de login
function Login([string]$query = "") {
    $json = & curl.exe -s -X POST "$Gateway/api/auth/login$query"
    return ($json | ConvertFrom-Json).accessToken
}

# Envoie une requete et renvoie le corps JSON decode (en-tetes supplementaires possibles)
function Json([string]$url, [string]$method = "GET", [string]$token = $null, [string[]]$headers = @()) {
    $curlArgs = @("-s", "-X", $method)
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    foreach ($h in $headers) { $curlArgs += @("-H", $h) }
    $curlArgs += $url
    $texte = (& curl.exe @curlArgs) -join ""
    if ([string]::IsNullOrWhiteSpace($texte)) { return $null }
    return ($texte | ConvertFrom-Json)
}

# Renvoie les en-tetes de la reponse sous forme de texte (pour chercher X-Trace-Id, etc.)
function Entetes([string]$url, [string]$method = "GET", [string]$token = $null) {
    $curlArgs = @("-s", "-D", "-", "-o", "NUL", "-X", $method)
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    $curlArgs += $url
    return ((& curl.exe @curlArgs) -join "`n")
}

# Compare le code obtenu au code attendu et affiche PASS / FAIL
function Check([string]$nom, [string]$attendu, [string]$obtenu) {
    if ($attendu -eq $obtenu) {
        Write-Host ("PASS  {0,-62} attendu {1}, obtenu {2}" -f $nom, $attendu, $obtenu) -ForegroundColor Green
    } else {
        Write-Host ("FAIL  {0,-62} attendu {1}, obtenu {2}" -f $nom, $attendu, $obtenu) -ForegroundColor Red
        $script:echecs++
    }
}

Write-Host "`n=== Verification du gateway : $Gateway ===`n"

$valide  = Login ""                       # token valide (15 min)
$expire  = Login "?ttl=-300"              # token deja expire depuis 5 minutes
$admin   = Login "?role=ADMIN"           # token d'un administrateur
$inconnu = Login "?role=HACKER"          # signature valide mais role non reconnu

# Token falsifie (signature altérée) : on modifie un caractere au MILIEU de la signature.
# Attention : ne pas toucher au dernier caractere, il contient des bits de remplissage ignores
# par le decodeur base64 (la signature resterait identique et le token serait encore valide).
$parts = $valide.Split(".")
$sig = $parts[2].ToCharArray()
$sig[100] = if ($sig[100] -eq "A") { "B" } else { "A" }
$falsifie = $parts[0] + "." + $parts[1] + "." + (-join $sig)

# Escalade de role : un attaquant remplace USER par ADMIN dans le payload sans pouvoir refaire la signature
function FromB64Url([string]$s) {
    $s = $s.Replace("-", "+").Replace("_", "/")
    if ($s.Length % 4 -eq 2) { $s += "==" } elseif ($s.Length % 4 -eq 3) { $s += "=" }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}
function ToB64Url([string]$t) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($t)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}
$payloadAdmin = (FromB64Url $parts[1]) -replace '"role"\s*:\s*"USER"', '"role":"ADMIN"'
$escalade = $parts[0] + "." + (ToB64Url $payloadAdmin) + "." + $parts[2]

Check "Sante du gateway (route publique)"                  "200" (Code "$Gateway/actuator/health")
Check "Inscription sans token (route publique)"            "200" (Code "$Gateway/api/users/register" "POST")
Check "Login sans token (route publique)"                  "200" (Code "$Gateway/api/auth/login" "POST")
Check "Token obtenu non vide"                              "oui" $(if ($valide) { "oui" } else { "non" })
Check "Route protegee SANS token"                          "401" (Code "$Gateway/api/accounts/42/balance")
Check "Route protegee avec token VALIDE"                   "200" (Code "$Gateway/api/accounts/42/balance" "GET" $valide)
Check "Route protegee avec token EXPIRE"                   "401" (Code "$Gateway/api/accounts/42/balance" "GET" $expire)
Check "Route protegee avec token FALSIFIE"                 "401" (Code "$Gateway/api/accounts/42/balance" "GET" $falsifie)
Check "Token avec ROLE modifie USER -> ADMIN (signature cassee)" "401" (Code "$Gateway/api/accounts/42/balance" "GET" $escalade)
Check "Route protegee avec un faux token (abc.def.ghi)"    "401" (Code "$Gateway/api/accounts/42/balance" "GET" "abc.def.ghi")
Check "Route inconnue SANS token (ne rien reveler)"        "401" (Code "$Gateway/api/inconnu")
Check "Route inconnue avec token valide"                   "404" (Code "$Gateway/api/inconnu" "GET" $valide)


Write-Host "`n--- B4 : roles, routes internes, erreurs et en-tetes ---`n"
$UID_ATTENDU = "0b2f6a4e-1c1d-4f3e-9d57-8a3c1f2e9b10"   # sub emis par le faux Identity

# Controle d'acces par role
Check "Route ADMIN avec token USER"                        "403" (Code "$Gateway/api/accounts/42/unblock" "POST" $valide)
Check "Route ADMIN avec token ADMIN"                       "200" (Code "$Gateway/api/accounts/42/unblock" "POST" $admin)
Check "Route ADMIN sans token"                             "401" (Code "$Gateway/api/accounts/42/unblock" "POST")
Check "Token a role inconnu (HACKER) sur route protegee"   "403" (Code "$Gateway/api/accounts/42/balance" "GET" $inconnu)

# Routes internes : bloquees pour tous, administrateurs compris
Check "Route INTERNE debit avec token USER"                "403" (Code "$Gateway/api/accounts/42/debit" "POST" $valide)
Check "Route INTERNE debit avec token ADMIN"               "403" (Code "$Gateway/api/accounts/42/debit" "POST" $admin)
Check "Route INTERNE credit avec token USER"               "403" (Code "$Gateway/api/accounts/42/credit" "POST" $valide)
Check "Route INTERNE debit avec barre finale"              "403" (Code "$Gateway/api/accounts/42/debit/" "POST" $valide)
Check "Route INTERNE debit sans token"                     "401" (Code "$Gateway/api/accounts/42/debit" "POST")

# Format d'erreur du contrat : code, message, traceId
$erreur401 = Json "$Gateway/api/accounts/42/balance"
Check "Erreur 401 : champ code = UNAUTHORIZED"             "UNAUTHORIZED" $erreur401.code
Check "Erreur 401 : traceId present"                       "oui" $(if ($erreur401.traceId) { "oui" } else { "non" })
$erreur403 = Json "$Gateway/api/accounts/42/unblock" "POST" $valide
Check "Erreur 403 : champ code = FORBIDDEN"                "FORBIDDEN" $erreur403.code
$erreur404 = Json "$Gateway/api/inconnu" "GET" $valide
Check "Erreur 404 : champ code = NOT_FOUND"                "NOT_FOUND" $erreur404.code

# Le traceId du corps est le meme que l'en-tete X-Trace-Id de la reponse
Check "En-tete X-Trace-Id present dans la reponse"         "oui" $(if ((Entetes "$Gateway/api/accounts/42/balance") -match "X-Trace-Id: ") { "oui" } else { "non" })

# En-tetes d'identite : calcules par le gateway, jamais pris chez le client
$echo = Json "$Gateway/api/accounts/42/balance" "GET" $valide @("X-User-Role: ADMIN", "X-User-Id: pirate")
Check "Service recoit X-User-Role = USER (usurpation ignoree)" "USER" $echo.headers.'X-user-role'
Check "Service recoit X-User-Id = sub du JWT"              $UID_ATTENDU $echo.headers.'X-user-id'
Check "Service recoit l'en-tete Authorization"             "oui" $(if ($echo.headers.Authorization -like "Bearer *") { "oui" } else { "non" })
Check "Service recoit un X-Trace-Id"                       "oui" $(if ($echo.headers.'X-trace-id') { "oui" } else { "non" })
$publique = Json "$Gateway/api/users/register" "POST" $null @("X-User-Role: ADMIN")
Check "Route publique : X-User-Role usurpe supprime"       "aucun" $(if ($publique.headers.'X-user-role') { $publique.headers.'X-user-role' } else { "aucun" })

Write-Host ""
if ($script:echecs -eq 0) { Write-Host "Tous les controles sont passes." -ForegroundColor Green }
else { Write-Host "$($script:echecs) controle(s) en echec." -ForegroundColor Red }
exit $script:echecs
