package com.neobank.gateway.ratelimit;

import com.neobank.gateway.config.GatewayProperties;
import com.neobank.gateway.ratelimit.RateLimitGate.Bucket;
import com.neobank.gateway.web.ErrorResponses;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rate limiting par UTILISATEUR (cahier des charges §6), complément de la limite par IP.
 * L'identité n'est connue qu'après la validation du JWT : ce filtre s'exécute donc APRÈS Spring Security
 * (ordre -90, la chaîne de sécurité est à -100). La clé est le claim "sub" : un utilisateur qui change d'IP
 * ou qui partage une IP (réseau d'entreprise) reste compté une seule fois.
 * Les requêtes anonymes (routes publiques) ne passent pas ici : elles sont déjà bornées par la limite par IP.
 * Panne de Redis : fail-open, comme pour la limite par IP.
 */
@Component
public class UserRateLimitFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UserRateLimitFilter.class);

    private final RateLimitGate gate;
    private final GatewayProperties.RateLimit conf;
    private final MeterRegistry meters;

    public UserRateLimitFilter(RateLimitGate gate, GatewayProperties properties, MeterRegistry meters) {
        this.gate = gate;
        this.conf = properties.rateLimit();
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!conf.enabled() || path.startsWith("/actuator/health")) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken().getSubject())
                .filter(subject -> subject != null && !subject.isBlank())
                // Optional + defaultIfEmpty plutôt que switchIfEmpty sur le résultat : la chaîne se termine par un Mono<Void>
                // vide, et switchIfEmpty l'exécuterait une seconde fois
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(subject -> subject.isPresent()
                        ? limit(exchange, chain, subject.get(), path)
                        : chain.filter(exchange));   // pas d'identité JWT : rien à compter ici
    }

    /** Interroge le limiteur pour cet utilisateur puis laisse passer ou répond 429. */
    private Mono<Void> limit(ServerWebExchange exchange, WebFilterChain chain, String subject, String path) {
        return gate.check(Bucket.USER, subject)
                .onErrorResume(error -> {
                    log.warn("Rate limiting par utilisateur indisponible, requête autorisée (fail-open) : {}", error.toString());
                    return Mono.just(new RateLimitGate.Verdict(true, Map.of()));
                })
                .flatMap(verdict -> {
                    if (verdict.allowed()) {
                        return chain.filter(exchange);
                    }
                    // L'identifiant n'est pas écrit en clair dans les logs d'accès au-delà du strict nécessaire
                    log.warn("429 {} user={} bucket=USER", path, subject);
                    meters.counter("gateway.ratelimit.rejected", "bucket", "user").increment();
                    exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
                    return ErrorResponses.write(exchange, 429, "TOO_MANY_REQUESTS",
                            "Trop de requêtes, réessayez plus tard");
                });
    }

    @Override
    public int getOrder() {
        return -90;   // après la chaîne de sécurité (-100) : le JWT est déjà validé
    }
}
