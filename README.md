# API Gateway - Digital Banking Platform

Point d'entree unique de la plateforme : routage `/api/**` vers les services, validation du JWT (RS256),
roles USER/ADMIN, rate limiting par IP (Redis), CORS, circuit breaker, logs JSON, erreurs au format
`{code, message, traceId}`. Contrat d'interface : `Contrat_Interface_API_Gateway.pdf`.

## Prerequis
Java 21, Maven 3.9, Docker Desktop **demarre** (les tests d'integration utilisent un vrai Redis).

## Commandes
| Action | Commande |
|---|---|
| Compiler et lancer les tests | `mvn verify` (Docker doit tourner, sinon les tests Redis echouent) |
| Faux services (dev) | `java tools/MockServices.java` |
| Lancer le gateway | `mvn spring-boot:run` (Redis requis sur localhost:6379, sinon fail-open) |
| Stack Docker | `docker compose up -d --build` |
| Verifications de bout en bout | `powershell -ExecutionPolicy Bypass -File tools\verifier.ps1` (puis `verifier-b5/b6/b7.ps1`) |

## Configuration (variables d'environnement)
Voir `.env.example`. Les valeurs par defaut servent au developpement local.

| Groupe | Variables |
|---|---|
| Services aval | `IDENTITY_SERVICE_URL`, `CORE_BANKING_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `NOTIFICATION_SERVICE_URL` |
| JWT | `JWT_ISSUER`, `JWKS_URI`, `ADMIN_PATHS` |
| Rate limiting / CORS | `REDIS_HOST`, `REDIS_PORT`, `RATE_LIMIT_*`, `CORS_ALLOWED_ORIGINS` |
| Resilience | `RESILIENCE_TIMEOUT_MS`, `RESILIENCE_*` |
| Logs | `LOG_LEVEL`, `LOG_FORMAT` (ecs ou logstash) |

## Pipeline CI (B11)
`Jenkinsfile` : Gitleaks, build + tests (JaCoCo), SonarQube + Quality Gate, Dependency-Check, SBOM CycloneDX.
Mise en place et rejeu local : voir `ci/README-CI.md` (script `ci/run-checks-local.ps1`).

## Points d'attention pour le deploiement Docker Swarm (B13)
- Reseau : `banking-network`, nom des services identique au contrat (section 2).
- **IP des clients** : derriere le routage interne de Swarm, le gateway voit la meme adresse pour tous les clients ;
  le rate limiting par IP deviendrait alors partage. Publier le port 8080 en mode `host`, ou regler
  `RATE_LIMIT_TRUSTED_PROXIES` selon le proxy reel.
- Redis : service interne, sans port publie. Le gateway reste disponible si Redis tombe (fail-open).
- Secrets : aucun secret dans l'image ni dans Git.

## Bloc B15 : compléments au cahier des charges

| Besoin (cahier des charges) | Mise en œuvre |
|---|---|
| Rate limiting par utilisateur (§6) | `UserRateLimitFilter` : clé = claim `sub`, 10 req/s, pointe 20 (`RATE_LIMIT_USER_*`) |
| Retry sur appels idempotents (§7) | filtre `retry` des routes : GET seulement, 2 tentatives, 502/503/504 |
| Validation stricte des entrées (§10) | `RequestValidationFilter` : 413 (1 Mio, 10 Mio KYC), 415 (JSON seulement), 400 (`Idempotency-Key`) |
| TLS (§10) | `SSL_ENABLED=true` + `docker-stack.tls.yml` ; réseau overlay chiffré (`--opt encrypted`) |
| Agrégation légère (§6) | `GET /api/dashboard/{accountId}` : solde + opérations + notifications, dégradé si une partie échoue |
| Métriques et alertes (§17) | Prometheus sur le port interne 9090 (`/metrics`) ; `ops/prometheus.yml`, `ops/alerts.yml` |
| Performance p95 < 300 ms (§20) | `java tools/LoadTest.java` (charge ouverte, code de sortie 0 si p95 < 300 ms) |
| Recette de bout en bout (§20, §24) | `java tools/E2E.java` (26 contrôles ; `RATE=true`, `MOCK=true` pour les contrôles optionnels) |

Vérification : `mvn clean verify` (les tests de B15 sont `RequestValidationFilterTests`, `UserRateLimitFilterTests`,
`DashboardAssemblyTests`, `RoutesConfigTests`).
