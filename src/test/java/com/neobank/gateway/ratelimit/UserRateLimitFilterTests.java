package com.neobank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.config.GatewayProperties;
import com.neobank.gateway.ratelimit.RateLimitGate.Bucket;
import com.neobank.gateway.ratelimit.RateLimitGate.Verdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Limite par utilisateur : la clé est le claim "sub", les anonymes ne sont pas comptés, Redis en panne = fail-open. */
class UserRateLimitFilterTests {

    private final List<String> appels = new ArrayList<>();
    private final AtomicBoolean suite = new AtomicBoolean(false);
    private final WebFilterChain chaine = exchange -> {
        suite.set(true);
        return Mono.empty();
    };

    private static GatewayProperties config(boolean actif) {
        return new GatewayProperties(null, null, new GatewayProperties.RateLimit(actif, 0, 10, 20, 40, 10, 20),
                null, null, null);
    }

    private UserRateLimitFilter filtre(boolean autorise, boolean actif) {
        RateLimitGate porte = (bucket, cle) -> {
            appels.add(bucket + "|" + cle);
            return Mono.just(new Verdict(autorise, Map.of()));
        };
        return new UserRateLimitFilter(porte, config(actif), new SimpleMeterRegistry());
    }

    private static JwtAuthenticationToken utilisateur(String sub) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", sub, "role", "USER"));
        return new JwtAuthenticationToken(jwt);
    }

    private static MockServerWebExchange requete(String chemin) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(chemin).build());
    }

    @Test
    void laCleEstLeSubDuJwtEtLaRequetePasseDansLaLimite() {
        filtre(true, true).filter(requete("/api/accounts/1/balance"), chaine)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(utilisateur("user-42"))).block();

        assertThat(appels).containsExactly(Bucket.USER + "|user-42");
        assertThat(suite).isTrue();
    }

    @Test
    void auDelaDeLaLimiteReponse429JsonAvecRetryAfter() {
        MockServerWebExchange e = requete("/api/accounts/1/balance");
        filtre(false, true).filter(e, chaine)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(utilisateur("user-42"))).block();

        assertThat(suite).isFalse();
        assertThat(e.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(e.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(e.getResponse().getBodyAsString().block()).contains("TOO_MANY_REQUESTS");
    }

    @Test
    void uneRequeteAnonymeNEstPasComptee() {
        filtre(false, true).filter(requete("/api/auth/login"), chaine).block();

        assertThat(appels).isEmpty();
        assertThat(suite).isTrue();
    }

    @Test
    void redisEnPanneLaisseToutPasser() {
        RateLimitGate enPanne = (bucket, cle) -> Mono.error(new IllegalStateException("redis down"));
        new UserRateLimitFilter(enPanne, config(true), new SimpleMeterRegistry())
                .filter(requete("/api/accounts/1/balance"), chaine)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(utilisateur("user-42"))).block();

        assertThat(suite).isTrue();
    }

    @Test
    void desactiveOuSondeDeSanteAucuneLimite() {
        filtre(false, false).filter(requete("/api/accounts/1/balance"), chaine)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(utilisateur("u"))).block();
        assertThat(suite).isTrue();
        suite.set(false);
        filtre(false, true).filter(requete("/actuator/health/liveness"), chaine)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(utilisateur("u"))).block();
        assertThat(suite).isTrue();
        assertThat(appels).isEmpty();
    }
}
