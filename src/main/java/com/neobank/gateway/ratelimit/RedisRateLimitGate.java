package com.neobank.gateway.ratelimit;

import com.neobank.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Implémentation Redis : algorithme "token bucket" fourni par Spring Cloud Gateway (script Lua atomique,
 * donc cohérent même avec plusieurs réplicas du gateway qui partagent le même Redis).
 *
 * Chaque seau se remplit de "replenishRate" jetons par seconde jusqu'à "burstCapacity" ; une requête consomme
 * "requestedTokens" jetons. Seau AUTH : 1 jeton/s, capacité 60, coût 60/N par requête => N tentatives par
 * minute (pointe de N d'un coup). Seau GLOBAL : débit moyen et pointe par IP, coût 1.
 *
 * Les limiteurs ne sont volontairement PAS déclarés comme beans : Spring Cloud Gateway créerait alors son propre
 * filtre RequestRateLimiter avec deux candidats ambigus au démarrage. On les initialise à la main.
 */
@Component
public class RedisRateLimitGate implements RateLimitGate {

    private final RedisRateLimiter auth;
    private final RedisRateLimiter global;
    private final RedisRateLimiter user;

    public RedisRateLimitGate(GatewayProperties properties, ApplicationContext context) {
        GatewayProperties.RateLimit conf = properties.rateLimit();
        int perMinute = Math.max(1, conf.authPerMinute());
        this.auth = init(new RedisRateLimiter(1, 60, Math.max(1, 60 / perMinute)), context);
        this.global = init(new RedisRateLimiter(Math.max(1, conf.ipPerSecond()), Math.max(1, conf.ipBurst()), 1), context);
        // Seau USER : même mécanisme, clé = identifiant de l'utilisateur (claim "sub"), indépendant de son IP
        this.user = init(new RedisRateLimiter(Math.max(1, conf.userPerSecond()), Math.max(1, conf.userBurst()), 1), context);
    }

    /** Branche le limiteur sur le client Redis et le script Lua du contexte Spring. */
    private static RedisRateLimiter init(RedisRateLimiter limiter, ApplicationContext context) {
        limiter.setApplicationContext(context);
        return limiter;
    }

    @Override
    public Mono<Verdict> check(Bucket bucket, String key) {
        RedisRateLimiter limiter = switch (bucket) {
            case AUTH -> auth;
            case USER -> user;
            case GLOBAL -> global;
        };
        String name = bucket.name().toLowerCase();
        // Le préfixe garde les deux seaux séparés dans Redis (la clé est utilisée telle quelle)
        return limiter.isAllowed(name, name + ":" + key)
                .map(response -> new Verdict(response.isAllowed(), response.getHeaders()));
    }
}
