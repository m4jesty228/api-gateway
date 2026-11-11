# Jenkins pour le pipeline du gateway : JDK 21 (déjà dans l'image de base), Maven, client Docker, Gitleaks.
# Les outils sont copiés depuis des images officielles : mêmes versions que celles utilisées pour construire l'image du gateway.
FROM jenkins/jenkins:lts-jdk21

USER root

# Maven 3.9 (même famille que l'étape de build du Dockerfile du gateway)
COPY --from=maven:3.9-eclipse-temurin-21 /usr/share/maven /opt/maven
RUN ln -s /opt/maven/bin/mvn /usr/local/bin/mvn

# Client Docker récent : un vieux client est refusé par Docker 29+ (version d'API minimale relevée)
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker

# Gitleaks : binaire statique. Pour figer la version, remplacer :latest par un numéro de version vérifié.
COPY --from=ghcr.io/gitleaks/gitleaks:latest /usr/bin/gitleaks /usr/local/bin/gitleaks

USER jenkins

# Plugins nécessaires au Jenkinsfile
RUN jenkins-plugin-cli --plugins \
    workflow-aggregator git credentials-binding timestamper junit sonar ws-cleanup
