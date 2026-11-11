package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * B8 : tests purement unitaires du format d'erreur du contrat (§6).
 * Aucun contexte Spring, aucun réseau.
 */
class ErrorResponsesTests {

    @Test
    void jsonProduitLeFormatExactDuContrat() {
        String json = ErrorResponses.json("UNAUTHORIZED", "Token absent, invalide ou expiré", "abc-123");
        assertThat(json).isEqualTo(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Token absent, invalide ou expiré\",\"traceId\":\"abc-123\"}");
    }

    @Test
    void jsonEchappeGuillemetsAntislashsEtControles() {
        String json = ErrorResponses.json("C\"ODE", "msg\n\t\\fin", "id");
        assertThat(json).contains("\\\"ODE")
                .contains("\\n")
                .contains("\\t")
                .contains("\\\\fin")
                .startsWith("{\"code\":\"")
                .endsWith("\"}");
    }

    @Test
    void writePoseLeStatutLeContentTypeEtLeCorps() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x").build());
        exchange.getAttributes().put(TraceIdFilter.ATTRIBUTE, "trace-b8");

        ErrorResponses.write(exchange, 403, "FORBIDDEN", "Accès refusé").block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .contains("application/json");
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"")
                .contains("\"message\":\"Accès refusé\"")
                .contains("\"traceId\":\"trace-b8\"");
    }

    @Test
    void traceIdOfPreferelAttributePuisHeaderPuisUuid() {
        MockServerWebExchange withAttr = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").build());
        withAttr.getAttributes().put(TraceIdFilter.ATTRIBUTE, "from-attr");
        assertThat(ErrorResponses.traceIdOf(withAttr)).isEqualTo("from-attr");

        MockServerWebExchange withHeader = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header(TraceIdFilter.HEADER, "from-header").build());
        assertThat(ErrorResponses.traceIdOf(withHeader)).isEqualTo("from-header");

        MockServerWebExchange empty = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());
        assertThat(ErrorResponses.traceIdOf(empty)).matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }
}
