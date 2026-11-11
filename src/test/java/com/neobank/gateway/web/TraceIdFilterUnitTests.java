package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * B8 : tests unitaires isolés de TraceIdFilter (génération, validation, propagation).
 */
class TraceIdFilterUnitTests {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void accepteUnTraceIdClientSurEtDeLongueurCorrecte() {
        assertThat(TraceIdFilter.resolve("demo-b8-0001")).isEqualTo("demo-b8-0001");
        assertThat(TraceIdFilter.resolve("A1b2C3d4-E5f6-7890-abcd-ef1234567890")).isEqualTo("A1b2C3d4-E5f6-7890-abcd-ef1234567890");
    }

    @Test
    void refuseLesTraceIdTropCourtsTropLongsOuAvecCaracteresInterdits() {
        assertThat(TraceIdFilter.resolve("short")).hasSize(36);           // < 8
        assertThat(TraceIdFilter.resolve("a".repeat(65))).hasSize(36);    // > 64
        assertThat(TraceIdFilter.resolve("bad space")).hasSize(36);
        assertThat(TraceIdFilter.resolve("bad\nnewline")).hasSize(36);
        assertThat(TraceIdFilter.resolve("bad\"quote")).hasSize(36);
        assertThat(TraceIdFilter.resolve(null)).hasSize(36);
        assertThat(TraceIdFilter.resolve("")).hasSize(36);
    }

    @Test
    void poseLeTraceIdSurLaReponseEtDansLesAttributs() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x").header(TraceIdFilter.HEADER, "client-trace-01").build());

        filter.filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdFilter.HEADER)).isEqualTo("client-trace-01");
        assertThat((String) exchange.getAttribute(TraceIdFilter.ATTRIBUTE)).isEqualTo("client-trace-01");
    }

    @Test
    void remplaceUnTraceIdClientDangereuxEtLePropageVersLAval() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x").header(TraceIdFilter.HEADER, "evil\nlog").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, e -> {
            forwarded.set(e);
            return Mono.empty();
        }).block();

        String generated = exchange.getResponse().getHeaders().getFirst(TraceIdFilter.HEADER);
        assertThat(generated).matches("^[0-9a-f-]{36}$");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(TraceIdFilter.HEADER)).isEqualTo(generated);
        // L'en-tête dangereux du client ne doit plus apparaître
        assertThat(forwarded.get().getRequest().getHeaders().get(TraceIdFilter.HEADER))
                .doesNotContain("evil\nlog");
    }
}
