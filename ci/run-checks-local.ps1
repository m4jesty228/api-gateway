# Rejoue en local les controles du pipeline (B11), sans Jenkins : utile pour les captures et pour deboguer.
# A lancer depuis le dossier du gateway (celui qui contient pom.xml), Docker Desktop demarre :
#   powershell -ExecutionPolicy Bypass -File ci\run-checks-local.ps1
# Variables optionnelles : NVD_API_KEY (scan SCA), SONAR_TOKEN (analyse SonarQube sur http://localhost:9000)
param([string]$SonarUrl = "http://localhost:9000")

$script:echecs = 0

# Execute une etape et affiche PASS / FAIL / SKIP selon le code retour
function Etape([string]$nom, [scriptblock]$action) {
    Write-Host "`n=== $nom ===" -ForegroundColor Cyan
    & $action
    if ($LASTEXITCODE -eq 0) {
        Write-Host "PASS  $nom" -ForegroundColor Green
    } else {
        Write-Host "FAIL  $nom (code $LASTEXITCODE)" -ForegroundColor Red
        $script:echecs++
    }
}

# 1. Secrets : Gitleaks dans un conteneur, sur le dossier courant (pas besoin de depot Git)
Etape "Secrets - Gitleaks" {
    docker run --rm -v "${PWD}:/repo" ghcr.io/gitleaks/gitleaks:latest dir /repo `
        --config /repo/.gitleaks.toml --redact --no-banner --exit-code 1
}

# 2. Build, tests (Docker requis pour Redis) et couverture JaCoCo
Etape "Build et tests" { mvn -B -ntp clean verify }

# 3. SAST : seulement si un jeton SonarQube est fourni
if ($env:SONAR_TOKEN) {
    Etape "SAST - SonarQube" {
        mvn -B -ntp org.sonarsource.scanner.maven:sonar-maven-plugin:sonar "-Dsonar.host.url=$SonarUrl" "-Dsonar.token=$env:SONAR_TOKEN"
    }
} else {
    Write-Host "`nSKIP  SAST - SonarQube (definir SONAR_TOKEN pour l'activer)" -ForegroundColor Yellow
}

# 4. SCA : seulement si une cle NVD est fournie (la 1re execution telecharge la base : plusieurs minutes)
if ($env:NVD_API_KEY) {
    Etape "SCA - Dependency-Check" { mvn -B -ntp dependency-check:check }
} else {
    Write-Host "`nSKIP  SCA - Dependency-Check (definir NVD_API_KEY pour l'activer)" -ForegroundColor Yellow
}

# 5. SBOM CycloneDX
Etape "SBOM - CycloneDX" { mvn -B -ntp cyclonedx:makeAggregateBom }

Write-Host "`nRapports : target\site\jacoco\index.html, target\dependency-check-report.html, target\bom.json"
if ($script:echecs -eq 0) { Write-Host "Aucun echec." -ForegroundColor Green } else { Write-Host "$($script:echecs) etape(s) en echec." -ForegroundColor Red }
exit $script:echecs
