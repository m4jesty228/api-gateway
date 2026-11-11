package com.neobank.gateway.ratelimit;

import com.neobank.gateway.config.GatewayProperties;
import com.neobank.gateway.ratelimit.RateLimitGate.Bucket;
import com.neobank.gateway.web.ErrorResponses;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rate limiting par IP, exécuté AVANT la sécurité : même un flot de requêtes sans token (qui finiraient en 401)
 * ou vers des routes inconnues est freiné. Ordre : TraceIdFilter (le 429 porte un traceId) puis CORS (le 429
 * porte les en-têtes CORS, sinon le navigateur cache la réponse au JavaScript du frontend), puis ce filtre.
 *
 * Choix de conception : limite par IP uniquement, car l'identité de l'utilisateur n'est connue qu'après la
 * validation du JWT. Si le rate limiting par utilisateur devient nécessaire, un second filtre pourra se placer
 * après la sécurité.
 *
 * Panne de Redis : on laisse passer (fail-open) plutôt que de bloquer toute la banque à cause d'un cache.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Routes publiques sensibles (contrat §3) : cibles privilégiées du brute force et du bourrage d'inscriptions. */
    private static final Set<String> AUTH_PATHS = Set.of("/api/auth/login", "/api/auth/refresh", "/api/users/register");

    private final RateLimitGate gate;
    private final GatewayProperties.RateLimit conf;

    public RateLimitFilter(RateLimitGate gate, GatewayProperties properties) {
        this.gate = gate;
        this.conf = properties.rateLimit();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = normalize(exchange.getRequest().getPath().value());
        // Désactivable par configuration ; les sondes de santé (Swarm/Kubernetes) ne sont jamais limitées
        if (!conf.enabled() || path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return chain.filter(exchange);
        }

        String ip = ClientIpResolver.resolve(exchange, conf.trustedProxies());
        boolean sensible = HttpMethod.POST.equals(exchange.getRequest().getMethod()) && AUTH_PATHS.contains(path);
        Bucket bucket = sensible ? Bucket.AUTH : Bucket.GLOBAL;
        // Seau AUTH : un compteur par route ET par IP (login et register ne se pénalisent pas entre eux)
        String key = sensible ? path + ":" + ip : ip;

        return gate.check(bucket, key)
                .onErrorResume(error -> {
                    log.warn("Rate limiting indisponible, requête autorisée (fail-open) : {}", error.toString());
                    return Mono.just(new RateLimitGate.Verdict(true, Map.of()));
                })
                .flatMap(verdict -> {
                    String remaining = verdict.headers().get("X-RateLimit-Remaining");
                    if (remaining != null) {
                        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", remaining);
                    }
                    if (verdict.allowed()) {
                        return chain.filter(exchange);
                    }
                    log.warn("429 {} ip={} bucket={}", path, ip, bucket);
                    exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter(bucket)));
                    return ErrorResponses.write(exchange, 429, "TOO_MANY_REQUESTS",
                            "Trop de requêtes, réessayez plus tard");
                });
    }

    /** Secondes à attendre avant de retenter : le temps de récupérer le jeton consommé par une requête. */
    private long retryAfter(Bucket bucket) {
        return bucket == Bucket.AUTH ? Math.max(1, 60 / Math.max(1, conf.authPerMinute())) : 1;
    }

    /** "/api/auth/login/" et "/api/auth/login" désignent la même route : on retire la barre finale. */
    private static String normalize(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;   // après TraceIdFilter (+0) et CORS (+2), avant la sécurité
    }
}
