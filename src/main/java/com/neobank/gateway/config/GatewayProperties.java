package com.neobank.gateway.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration externalisée du gateway (préfixe "gateway" dans application.yml).
 * Chaque valeur vient d'une variable d'environnement : en local c'est localhost,
 * sous Docker Swarm c'est le nom DNS du service (ex. http://identity-service:8081).
 * Changer un hôte ou un port à l'intégration ne demande donc aucune recompilation.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(Services services, Security security, RateLimit rateLimit, Cors cors,
                                Validation validation, Metrics metrics) {

    /** Adresses des 4 services du MVP, telles que définies dans le contrat d'interface (§2). */
    public record Services(URI identity, URI banking, URI payment, URI notification) {
    }

    /**
     * Paramètres de validation du JWT (contrat §4) :
     * issuer attendu dans le claim "iss", adresse JWKS où récupérer la clé publique d'Identity,
     * et motifs de chemins réservés au rôle ADMIN (liste fournie par les services, §3).
     */
    public record Security(String issuer, URI jwksUri, List<String> adminPaths) {
    }

    /**
     * Rate limiting par IP (B5). trustedProxies : nombre de proxys de confiance devant le gateway (0 = aucun).
     * authPerMinute : tentatives par minute sur login/refresh/register ; ipPerSecond et ipBurst : débit moyen
     * et pointe autorisés sur toutes les autres routes. userPerSecond et userBurst : débit moyen et pointe par
     * utilisateur authentifié (claim "sub"), appliqués en plus de la limite par IP (cahier des charges §6).
     */
    public record RateLimit(boolean enabled, int trustedProxies, int authPerMinute, int ipPerSecond, int ipBurst,
                            int userPerSecond, int userBurst) {
    }

    /**
     * Validation stricte des entrées au niveau du gateway (cahier des charges §10) : taille maximale du corps,
     * type de contenu accepté et format de l'en-tête Idempotency-Key. Le schéma métier reste validé par chaque service.
     */
    public record Validation(boolean enabled, long maxBodyBytes, long kycMaxBodyBytes) {
    }

    /** Export des métriques Prometheus sur un port interne, jamais publié (cahier des charges §17). 0 = désactivé. */
    public record Metrics(int port) {
    }

    /** CORS : origines du frontend autorisées à appeler le gateway depuis un navigateur. */
    public record Cors(List<String> allowedOrigins) {
    }
}
