package com.neobank.gateway.web;

import com.neobank.gateway.config.GatewayProperties;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS pour le frontend React. Placé AVANT la sécurité : la requête de pré-vérification (OPTIONS) que le
 * navigateur envoie sans token est ainsi traitée ici et ne reçoit pas un 401. Les réponses 401, 403 et 429
 * portent aussi les en-têtes CORS, sinon le navigateur les masque au JavaScript (le frontend ne pourrait plus
 * détecter qu'il doit renouveler son token).
 */
@Component
public class GatewayCorsFilter extends CorsWebFilter implements Ordered {

    public GatewayCorsFilter(GatewayProperties properties) {
        super(configurationSource(properties.cors().allowedOrigins()));
    }

    /** Public pour être testé seul. Refuse "*" : incompatible avec allowCredentials et dangereux. */
    public static UrlBasedCorsConfigurationSource configurationSource(List<String> origins) {
        if (origins == null || origins.isEmpty() || origins.stream().anyMatch(o -> o.isBlank() || o.contains("*"))) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS doit lister des origines précises (jamais \"*\")");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Idempotency-Key DOIT figurer ici : sans elle, le navigateur bloque les virements (pré-vérification refusée)
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-Trace-Id"));
        // En-têtes de réponse lisibles par le JavaScript du frontend
        config.setExposedHeaders(List.of("X-Trace-Id", "Retry-After", "X-RateLimit-Remaining"));
        config.setAllowCredentials(true);   // cookie httpOnly du refresh token (contrat sécurité §10)
        config.setMaxAge(3600L);            // le navigateur mémorise la pré-vérification 1 h

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;   // après TraceIdFilter, avant le rate limiting et la sécurité
    }
}
