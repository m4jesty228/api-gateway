package com.neobank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.config.GatewayProperties;
import com.neobank.gateway.ratelimit.RateLimitGate.Bucket;
import com.neobank.gateway.ratelimit.RateLimitGate.Verdict;
import com.neobank.gateway.web.GatewayCorsFilter;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Comportement du filtre de rate limiting, avec une fausse porte (aucun Redis nécessaire). */
class RateLimitFilterTests {

    private final List<String> appels = new ArrayList<>();
    private final AtomicBoolean suite = new AtomicBoolean(false);
    private final WebFilterChain chaine = exchange -> {
        suite.set(true);
        return Mono.empty();
    };

    private static GatewayProperties config(boolean actif) {
        return new GatewayProperties(null, null,
                new GatewayProperties.RateLimit(actif, 0, 10, 20, 40, 10, 20),
                new GatewayProperties.Cors(List.of("http://localhost:3000")), null, null);
    }

    private RateLimitFilter filtre(boolean autorise, boolean actif) {
        RateLimitGate porte = (bucket, cle) -> {
            appels.add(bucket + "|" + cle);
            return Mono.just(new Verdict(autorise, Map.of("X-RateLimit-Remaining", "3")));
        };
        return new RateLimitFilter(porte, config(actif));
    }

    private static MockServerWebExchange requete(HttpMethod methode, String chemin) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(methode, chemin)
                .remoteAddress(new InetSocketAddress("10.0.0.5", 40000)).build());
    }

    @Test
    void uneRequeteDansLaLimitePasseEtRecoitLeNombreRestant() {
        MockServerWebExchange exchange = requete(HttpMethod.GET, "/api/accounts/42/balance");

        filtre(true, true).filter(exchange, chaine).block();

        assertThat(suite).isTrue();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("3");
    }

    @Test
    void uneRequeteHorsLimiteRecoitUn429JsonAvecRetryAfterEtSansAtteindreLeService() {
        MockServerWebExchange exchange = requete(HttpMethod.POST, "/api/auth/login");

        filtre(false, true).filter(exchange, chaine).block();

        assertThat(suite).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("6");   // 60 s / 10 par minute
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"TOO_MANY_REQUESTS\"").contains("\"traceId\"");
    }

    @Test
    void loginPostUtiliseLeSeauStrictAvecUneCleParRouteEtParIp() {
        filtre(true, true).filter(requete(HttpMethod.POST, "/api/auth/login"), chaine).block();
        filtre(true, true).filter(requete(HttpMethod.POST, "/api/users/register"), chaine).block();

        assertThat(appels).containsExactly("AUTH|/api/auth/login:10.0.0.5", "AUTH|/api/users/register:10.0.0.5");
    }

    @Test
    void uneBarreFinaleNeContournePasLeSeauStrict() {
        filtre(true, true).filter(requete(HttpMethod.POST, "/api/auth/login/"), chaine).block();

        assertThat(appels).containsExactly("AUTH|/api/auth/login:10.0.0.5");
    }

    @Test
    void lesAutresRoutesUtilisentLeSeauGlobalParIp() {
        filtre(true, true).filter(requete(HttpMethod.GET, "/api/accounts/42/balance"), chaine).block();
        // GET sur /api/auth/login n'est pas une tentative de connexion : seau global
        filtre(true, true).filter(requete(HttpMethod.GET, "/api/auth/login"), chaine).block();

        assertThat(appels).containsExactly("GLOBAL|10.0.0.5", "GLOBAL|10.0.0.5");
    }

    @Test
    void lesSondesDeSanteNeSontJamaisLimitees() {
        filtre(false, true).filter(requete(HttpMethod.GET, "/actuator/health/liveness"), chaine).block();

        assertThat(suite).isTrue();
        assertThat(appels).isEmpty();
    }

    @Test
    void siRedisEstEnPanneLaRequetePasseFailOpen() {
        RateLimitGate enPanne = (bucket, cle) -> Mono.error(new IllegalStateException("Redis injoignable"));
        MockServerWebExchange exchange = requete(HttpMethod.POST, "/api/auth/login");

        new RateLimitFilter(enPanne, config(true)).filter(exchange, chaine).block();

        assertThat(suite).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();   // aucune erreur écrite
    }

    @Test
    void quandLeRateLimitingEstDesactiveLaPorteNEstPasConsultee() {
        filtre(false, false).filter(requete(HttpMethod.POST, "/api/auth/login"), chaine).block();

        assertThat(suite).isTrue();
        assertThat(appels).isEmpty();
    }

    @Test
    void ordreDesFiltresTraceIdPuisCorsPuisRateLimiting() {
        // CORS avant le rate limiting : un 429 porte les en-têtes CORS. Les deux après TraceIdFilter (ordre MIN_VALUE).
        GatewayCorsFilter cors = new GatewayCorsFilter(config(true));
        RateLimitFilter rateLimit = filtre(true, true);

        assertThat(cors.getOrder()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        assertThat(rateLimit.getOrder()).isGreaterThan(cors.getOrder());
    }
}
