// Pipeline du gateway (B11-B14) : secrets, build + tests, SAST, SCA, SBOM, image Docker, scan Trivy, publication,
// déploiement Swarm et scan OWASP ZAP. Ordre conforme au cahier des charges (sections 11 et 13).
// Les deux dernières étapes (déploiement, ZAP) sont désactivées par défaut : elles demandent un cluster Swarm.
// Versions des outils : uniquement dans pom.xml. Identifiants : dans Jenkins (jamais dans ce fichier).
pipeline {
    agent any

    options {
        timestamps()                                   // horodate chaque ligne du journal
        timeout(time: 90, unit: 'MINUTES')             // la 1re exécution de Dependency-Check est longue
        disableConcurrentBuilds()                      // un seul build à la fois (base NVD partagée)
        buildDiscarder(logRotator(numToKeepStr: '15')) // garde les 15 derniers builds
    }

    parameters {
        // Dossier du gateway dans le dépôt : "." si le dépôt ne contient que le gateway,
        // "services/api-gateway" dans le monorepo du groupe
        string(name: 'SERVICE_DIR', defaultValue: '.', description: 'Dossier du gateway dans le dépôt')
        booleanParam(name: 'RUN_DEPENDENCY_CHECK', defaultValue: true,
                description: 'Décocher pour sauter le scan SCA (déconseillé hors dépannage)')
        // Nom de l'image : "compte/api-gateway" sur Docker Hub, "ghcr.io/organisation/api-gateway" sur GitHub, etc.
        string(name: 'IMAGE_REPO', defaultValue: 'neobank/api-gateway', description: 'Dépôt de l\'image (registre/compte/nom)')
        // Vide pour Docker Hub ; "ghcr.io" pour GitHub, etc.
        string(name: 'REGISTRY_HOST', defaultValue: '', description: 'Adresse du registre (vide = Docker Hub)')
        // Désactivé par défaut tant que le registre du groupe n'est pas choisi
        booleanParam(name: 'PUSH_IMAGE', defaultValue: false, description: 'Publier l\'image sur le registre')
        // Déploiement : l'agent doit être (ou joindre) un manager Swarm, avec le secret redis_password et le réseau banking-network
        booleanParam(name: 'DEPLOY_STACK', defaultValue: false, description: 'Déployer la stack Swarm (docker-stack.yml)')
        string(name: 'STACK_NAME', defaultValue: 'gateway', description: 'Nom de la stack Swarm')
        // ZAP attaque l'application déployée : ne s'exécute qu'avec une adresse atteignable depuis un conteneur
        booleanParam(name: 'RUN_ZAP', defaultValue: false, description: 'Lancer OWASP ZAP sur le gateway déployé')
        string(name: 'ZAP_TARGET_URL', defaultValue: 'http://host.docker.internal:8080', description: 'Adresse du gateway vue depuis un conteneur')
        // zap-baseline.py = scan passif (rapide). zap-full-scan.py = scan actif, à réserver à un environnement de test
        choice(name: 'ZAP_SCRIPT', choices: ['zap-baseline.py', 'zap-full-scan.py'], description: 'Type de scan ZAP')
    }

    environment {
        // Dépôt Maven placé dans le volume de Jenkins : téléchargements et base NVD conservés entre les builds
        MVN = "mvn -B -ntp -Dmaven.repo.local=${JENKINS_HOME}/.m2/repository"
    }

    stages {

        stage('Checkout') {
            steps {
                script {
                    // Le tag de l'image est le SHA du commit : chaque image se rattache à un état exact du code
                    def scmVars = checkout scm
                    env.IMAGE_TAG = scmVars.GIT_COMMIT.take(8)
                }
            }
        }

        // 1. Secrets : étape la plus rapide, donc la première (fail-fast). Scanne tout l'historique Git.
        stage('Secrets - Gitleaks') {
            steps {
                sh "gitleaks git --redact --no-banner --exit-code 1 " +
                   "--config '${params.SERVICE_DIR}/.gitleaks.toml' " +
                   "--report-format json --report-path gitleaks-report.json ."
            }
            post {
                always {
                    archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
                }
            }
        }

        // 2. Compilation, tests unitaires et d'intégration (Redis réel via Testcontainers), couverture JaCoCo
        stage('Build et tests') {
            steps {
                dir(params.SERVICE_DIR) {
                    sh "${MVN} clean verify"
                }
            }
            post {
                always {
                    junit testResults: "${params.SERVICE_DIR == '.' ? '' : params.SERVICE_DIR + '/'}target/surefire-reports/*.xml", allowEmptyResults: true
                    archiveArtifacts artifacts: "${params.SERVICE_DIR == '.' ? '' : params.SERVICE_DIR + '/'}target/site/jacoco/**", allowEmptyArchive: true
                }
            }
        }

        // 3. SAST : analyse statique du code par SonarQube (injections, secrets en dur, code smells de sécurité)
        stage('SAST - SonarQube') {
            steps {
                dir(params.SERVICE_DIR) {
                    // withSonarQubeEnv injecte l'adresse du serveur et le jeton configurés dans Jenkins.
                    // Les variables sont développées par le shell (\$) : le jeton n'apparaît jamais dans le journal.
                    withSonarQubeEnv('sonarqube') {
                        sh "${MVN} org.sonarsource.scanner.maven:sonar-maven-plugin:sonar " +
                           "-Dsonar.host.url=\$SONAR_HOST_URL -Dsonar.token=\$SONAR_AUTH_TOKEN"
                    }
                }
            }
        }

        // Le pipeline s'arrête si le Quality Gate est rouge (nouvelle vulnérabilité bloquante ou critique)
        stage('Quality Gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        // 4. SCA : CVE connues dans les bibliothèques tierces. Échec dès une CVE CRITICAL (CVSS 9 ou plus)
        stage('SCA - Dependency-Check') {
            when { expression { params.RUN_DEPENDENCY_CHECK } }
            steps {
                dir(params.SERVICE_DIR) {
                    withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')]) {
                        sh "${MVN} dependency-check:check"
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: "${params.SERVICE_DIR == '.' ? '' : params.SERVICE_DIR + '/'}target/dependency-check-report.*", allowEmptyArchive: true
                }
            }
        }

        // 5. SBOM : inventaire des composants livrés (CycloneDX), conservé avec le build
        stage('SBOM - CycloneDX') {
            steps {
                dir(params.SERVICE_DIR) {
                    sh "${MVN} cyclonedx:makeAggregateBom"
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: "${params.SERVICE_DIR == '.' ? '' : params.SERVICE_DIR + '/'}target/bom.*", allowEmptyArchive: true
                }
            }
        }

        // 6. Image Docker : build multi-étapes (Dockerfile du service), tag = SHA court du commit
        stage('Image Docker') {
            steps {
                dir(params.SERVICE_DIR) {
                    sh "docker build -t ${params.IMAGE_REPO}:${env.IMAGE_TAG} ."
                }
            }
        }

        // 7. Scan de l'image par Trivy : échec dès une faille HIGH ou CRITICAL ayant un correctif disponible.
        //    Trivy tourne dans son propre conteneur ; le cache de sa base est conservé entre les builds.
        stage('Scan image - Trivy') {
            steps {
                // Rapport complet en JSON, conservé avec le build (n'échoue jamais).
                // Écrit par redirection de la sortie : aucun dossier à monter dans le conteneur Trivy.
                sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache/ " +
                   "aquasec/trivy:latest image --quiet --format json --severity HIGH,CRITICAL " +
                   "${params.IMAGE_REPO}:${env.IMAGE_TAG} > trivy-report.json"
                // Contrôle bloquant : code de sortie 1 si une faille corrigeable subsiste
                sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache/ " +
                   "aquasec/trivy:latest image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed " +
                   "${params.IMAGE_REPO}:${env.IMAGE_TAG}"
            }
            post {
                always {
                    archiveArtifacts artifacts: 'trivy-report.json', allowEmptyArchive: true
                }
            }
        }

        // 8. Publication : seulement si l'image a passé le scan et si PUSH_IMAGE est coché.
        //    Identifiants du registre : credential Jenkins "registry-credentials" (nom d'utilisateur + mot de passe ou jeton).
        stage('Publication image') {
            when { expression { params.PUSH_IMAGE } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'registry-credentials',
                                                  usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                    // Mot de passe passé par l'entrée standard : il n'apparaît ni dans le journal ni dans la liste des processus
                    sh "echo \"\$REG_PASS\" | docker login ${params.REGISTRY_HOST} -u \"\$REG_USER\" --password-stdin"
                    sh "docker push ${params.IMAGE_REPO}:${env.IMAGE_TAG}"
                }
            }
            post {
                always { sh 'docker logout || true' }
            }
        }

        // 9. Déploiement : docker stack deploy du fichier de stack (réseau, secret et limites décrits dedans).
        //    Seul le tag change d'un build à l'autre : IMAGE_TAG = SHA du commit. En cas d'échec de santé,
        //    Swarm annule seul la mise à jour (rollback configuré dans docker-stack.yml).
        stage('Déploiement Swarm') {
            when { expression { params.DEPLOY_STACK } }
            steps {
                dir(params.SERVICE_DIR) {
                    withEnv(["IMAGE_REPO=${params.IMAGE_REPO}", "IMAGE_TAG=${env.IMAGE_TAG}"]) {
                        sh "docker stack deploy --with-registry-auth -c docker-stack.yml ${params.STACK_NAME}"
                    }
                }
                // Attend que le gateway réponde avant de lancer les contrôles suivants (3 minutes au maximum)
                timeout(time: 3, unit: 'MINUTES') {
                    waitUntil {
                        script {
                            return sh(returnStatus: true,
                                      script: "curl -fsS ${params.ZAP_TARGET_URL}/actuator/health/liveness") == 0
                        }
                    }
                }
            }
        }

        // 10. DAST : OWASP ZAP contre l'application en marche, jamais en production (cahier des charges, section 13).
        //     Le conteneur ZAP tourne en root le temps du scan pour pouvoir écrire ses rapports ; les rapports sont
        //     récupérés par "docker cp" (aucun dossier de l'agent n'est monté). Le pipeline échoue sur une alerte FAIL.
        //     Sans token, ZAP ne voit que des 401 : il contrôle les en-têtes et l'absence de fuite, pas les routes protégées.
        stage('DAST - OWASP ZAP') {
            when { expression { params.RUN_ZAP } }
            steps {
                sh "docker rm -f zap-${env.BUILD_NUMBER} || true"
                // Un code de sortie 2 (avertissements) ne fait pas échouer ; 1 (FAIL) oui. -I ignore les avertissements.
                sh "docker run --name zap-${env.BUILD_NUMBER} -u root --add-host=host.docker.internal:host-gateway " +
                   "-v zap-wrk-${env.BUILD_NUMBER}:/zap/wrk ghcr.io/zaproxy/zaproxy:stable " +
                   "${params.ZAP_SCRIPT} -t ${params.ZAP_TARGET_URL} -r zap-report.html -J zap-report.json -I"
            }
            post {
                always {
                    sh "docker cp zap-${env.BUILD_NUMBER}:/zap/wrk/zap-report.html . || true"
                    sh "docker cp zap-${env.BUILD_NUMBER}:/zap/wrk/zap-report.json . || true"
                    sh "docker rm -f zap-${env.BUILD_NUMBER} || true"
                    sh "docker volume rm zap-wrk-${env.BUILD_NUMBER} || true"
                    archiveArtifacts artifacts: 'zap-report.*', allowEmptyArchive: true
                }
            }
        }

    }

    post {
        success { echo 'Pipeline terminé : tous les contrôles de sécurité sont passés.' }
        failure { echo 'Pipeline en échec : consulter le rapport de l\'étape rouge (artefacts du build).' }
        cleanup { cleanWs() }                          // vide l'espace de travail, pas le dépôt Maven ni la base NVD
    }
}
