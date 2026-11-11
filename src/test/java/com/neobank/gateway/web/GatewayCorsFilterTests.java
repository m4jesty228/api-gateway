package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobank.gateway.config.GatewayProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS : pré-vérification du navigateur, origines refusées, en-têtes lisibles par le frontend.
 * Les URL des requêtes de test sont absolues : Spring compare l'en-tête Origin au schéma/hôte/port de la requête
 * pour décider s'il s'agit d'une requête cross-origin, ce qui est impossible avec une URL relative.
 */
class GatewayCorsFilterTests {

    private static final String FRONT = "http://localhost:3000";

    private final AtomicBoolean suite = new AtomicBoolean(false);
    private final WebFilterChain chaine = exchange -> {
        suite.set(true);
        return Mono.empty();
    };
    private final GatewayCorsFilter filtre = new GatewayCorsFilter(new GatewayProperties(null, null, null,
            new GatewayProperties.Cors(List.of(FRONT)), null, null));

    private static MockServerWebExchange preverification(String origine, String entetesDemandes) {
        return MockServerWebExchange.from(MockServerHttpRequest.options("http://localhost:8080/api/payments/transfer")
                .header(HttpHeaders.ORIGIN, origine)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, entetesDemandes)
                .build());
    }

    @Test
    void laPreverificationDuFrontendEstAccepteeSansTokenEtSansAtteindreLaSecurite() {
        MockServerWebExchange exchange = preverification(FRONT, "authorization, idempotency-key, content-type");

        filtre.filter(exchange, chaine).block();

        HttpHeaders reponse = exchange.getResponse().getHeaders();
        assertThat(suite).isFalse();   // la requête s'arrête ici : pas de 401 pour une pré-vérification
        assertThat(reponse.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FRONT);
        assertThat(reponse.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
        assertThat(reponse.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS).toLowerCase()).contains("idempotency-key");
    }

    @Test
    void uneOrigineInconnueEstRefusee() {
        MockServerWebExchange exchange = preverification("http://evil.example", "authorization");

        filtre.filter(exchange, chaine).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void unEnTeteNonAutoriseEstRefuseEnPreverification() {
        MockServerWebExchange exchange = preverification(FRONT, "x-evil");

        filtre.filter(exchange, chaine).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void uneRequeteNormaleDuFrontendContinueEtPeutLireX_TraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://localhost:8080/api/accounts/42/balance").header(HttpHeaders.ORIGIN, FRONT).build());

        filtre.filter(exchange, chaine).block();

        HttpHeaders reponse = exchange.getResponse().getHeaders();
        assertThat(suite).isTrue();
        assertThat(reponse.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FRONT);
        assertThat(reponse.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("X-Trace-Id").contains("Retry-After");
    }

    @Test
    void uneOrigineJokerOuVideEstRefuseeAuDemarrage() {
        assertThatThrownBy(() -> GatewayCorsFilter.configurationSource(List.of("*")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GatewayCorsFilter.configurationSource(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
