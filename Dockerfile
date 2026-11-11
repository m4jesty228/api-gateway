# =============================================================================
# API Gateway — Dockerfile multi-stage (B10)
# Image minimale, utilisateur non-root, HEALTHCHECK sur /actuator/health
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 : build (JDK + Maven officiels)
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

# Cache des dépendances Maven : recopié seulement si pom.xml change
COPY pom.xml .
RUN mvn -q -B dependency:go-offline -DskipTests

# Sources puis packaging (tests déjà validés en B8/B9 ; on ne les rejoue pas ici)
COPY src ./src
RUN mvn -q -B package -DskipTests \
 && mv target/api-gateway-*.jar target/app.jar

# -----------------------------------------------------------------------------
# Stage 2 : runtime (JRE seulement)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Paquets minimaux : curl pour HEALTHCHECK, et fuseau horaire
RUN apk add --no-cache curl tzdata \
 && addgroup -S gateway && adduser -S -G gateway gateway

WORKDIR /app

# Jar uniquement — pas de sources, pas de .m2, pas de JDK
COPY --from=build /workspace/target/app.jar /app/app.jar

# Propriété non-root : le processus ne tourne jamais en root
USER gateway:gateway

EXPOSE 8080

# Sondes Swarm / Kubernetes : liveness + readiness via Actuator
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

# JVM raisonnable pour un conteneur (heap limitée, exit sur OOM)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
