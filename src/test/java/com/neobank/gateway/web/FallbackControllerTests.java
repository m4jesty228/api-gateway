package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** B7 : la réponse de secours est un 503 au format du contrat, avec le traceId de la requête. */
class FallbackControllerTests {

    private final FallbackController controleur = new FallbackController();

    @Test
    void renvoieUn503StructureAvecLeTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost:8080/fallback/payment").build());
        exchange.getAttributes().put(TraceIdFilter.ATTRIBUTE, "trace-test-0001");

        controleur.fallback("payment", exchange).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        String corps = exchange.getResponse().getBodyAsString().block();
        assertThat(corps).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("\"traceId\":\"trace-test-0001\"");
    }

    @Test
    void unNomDeServiceMalveillantNeCasseNiLaReponseNiLeJson() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost:8080/fallback/x").build());

        controleur.fallback("x\"\n{evil}", exchange).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("evil");
    }
}
