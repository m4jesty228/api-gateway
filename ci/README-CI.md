# Pipeline CI du gateway (B11)

Étapes du `Jenkinsfile`, dans l'ordre (le plus rapide d'abord) :

| Étape | Outil | Le pipeline échoue si |
|---|---|---|
| Secrets | Gitleaks | un secret est détecté (code ou historique Git) |
| Build et tests | Maven, JUnit, Testcontainers, JaCoCo | un test échoue |
| SAST | SonarQube | le Quality Gate est rouge |
| SCA | OWASP Dependency-Check | une CVE CRITICAL (CVSS 9 ou plus) sans exception documentée |
| SBOM | CycloneDX | la génération échoue |

Suite prévue : B12 (image Docker, scan Trivy, publication), B13 (déploiement Swarm), B14 (OWASP ZAP).

## Option A : tout rejouer en local, sans Jenkins (le plus rapide pour commencer)

Docker Desktop démarré, dans le dossier du gateway :

```
powershell -ExecutionPolicy Bypass -File ci\run-checks-local.ps1
```

Sans variable, le script fait Gitleaks, build + tests et SBOM. Pour activer les deux autres contrôles :
- SCA : `set NVD_API_KEY=ta-cle` (clé gratuite à demander sur nvd.nist.gov ; la première exécution télécharge la base NVD, plusieurs minutes).
- SAST : démarrer SonarQube (option B, étapes 1 à 3), créer un jeton, puis `set SONAR_TOKEN=ton-jeton`.

## Option B : Jenkins + SonarQube en conteneurs

**1. Démarrer la stack**
```
docker compose -f ci/docker-compose.ci.yml up -d --build
```
Le premier démarrage construit l'image Jenkins et télécharge SonarQube (plusieurs minutes). Docker Desktop doit disposer d'au moins 4 Go de mémoire.

**2. Configurer SonarQube** (http://localhost:9000)
1. Connexion `admin` / `admin`, puis changement du mot de passe demandé.
2. *My Account > Security* : générer un jeton (type *User token*) et le copier.
3. *Administration > Configuration > Webhooks > Create* : nom `jenkins`, URL `http://jenkins:8080/sonarqube-webhook/`.

**3. Configurer Jenkins** (http://localhost:8088)
1. Déverrouiller : `docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword`, puis créer l'administrateur. Les plugins nécessaires sont déjà installés (passer l'installation des plugins suggérés).
2. *Manage Jenkins > Credentials > Global > Add credentials*, type **Secret text** :
   - `sonar-token` : le jeton SonarQube ;
   - `nvd-api-key` : la clé NVD.
3. *Manage Jenkins > System > SonarQube servers > Add* : nom **`sonarqube`** (exactement), URL `http://sonarqube:9000`, authentification `sonar-token`.
4. *New Item > Pipeline* : *Pipeline script from SCM*, Git, URL du dépôt, branche, *Script Path* = chemin du Jenkinsfile (`Jenkinsfile`, ou `services/api-gateway/Jenkinsfile` dans le monorepo).
5. *Build with Parameters* : `SERVICE_DIR` = `.` (ou `services/api-gateway`).

Le dépôt doit être joignable depuis le conteneur Jenkins (GitHub par exemple ; un dépôt privé demande un identifiant de type *Username with password* ou jeton).

## À connaître

- **Première exécution de Dependency-Check** : téléchargement de la base NVD, de 10 à 30 minutes ou plus. Elle est conservée dans le volume Jenkins : les suivantes sont rapides.
- **Quality Gate** : le critère par défaut de SonarQube ("Sonar way") exige une couverture suffisante sur le code nouveau. S'il est rouge, consulter l'analyse avant de toucher au seuil.
- **Exceptions** : une CVE acceptée se déclare dans `dependency-check-suppressions.xml`, avec motif et date d'expiration, validée en revue de code.
- **Sécurité de ce laboratoire** : Jenkins tourne en root avec le socket Docker monté (nécessaire aux builds Docker et à Testcontainers). Ne pas reproduire tel quel en production.
- **Versions** : outils figés dans `pom.xml` (propriétés `*.version`), Gitleaks sur `:latest` dans `ci/jenkins/Dockerfile` (à figer après vérification).
