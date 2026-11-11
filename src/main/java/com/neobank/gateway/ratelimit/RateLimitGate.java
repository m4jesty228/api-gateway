package com.neobank.gateway.ratelimit;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Porte de décision du rate limiting : "cette clé a-t-elle encore le droit de passer ?".
 * Une interface plutôt que Redis en dur, pour tester le filtre sans serveur Redis.
 */
public interface RateLimitGate {

    /** AUTH : routes sensibles (anti brute force). GLOBAL : toutes les autres, par IP. USER : par utilisateur authentifié. */
    enum Bucket { AUTH, GLOBAL, USER }

    /** Décision (allowed) + en-têtes d'information fournis par le limiteur (ex. X-RateLimit-Remaining). */
    record Verdict(boolean allowed, Map<String, String> headers) {
    }

    Mono<Verdict> check(Bucket bucket, String key);
}
