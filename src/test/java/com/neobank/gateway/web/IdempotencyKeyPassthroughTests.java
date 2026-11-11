package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.security.UserHeadersFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Contrat §5 : l'en-tête Idempotency-Key traverse le gateway sans modification, jusqu'à Payment.
 * Si un futur filtre le supprime ou le réécrit, un client qui rejoue un virement après un timeout
 * serait débité deux fois : ce test garde cette garantie.
 */
class IdempotencyKeyPassthroughTests {

    private static final String CLE = "9f1c2b7e-3a44-4d0e-8b19-6f2a5c7d1e30";

    @Test
    void lesFiltresDuGatewayLaissentPasserIdempotencyKeyIntact() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("http://localhost:8080/api/payments/transfer").header("Idempotency-Key", CLE).build());
        AtomicReference<ServerWebExchange> vuParLeService = new AtomicReference<>();

        // Même ordre que le gateway : TraceIdFilter (WebFilter), puis UserHeadersFilter (filtre de routage)
        new TraceIdFilter().filter(exchange, apresTrace ->
                new UserHeadersFilter().filter(apresTrace, apresIdentite -> {
                    vuParLeService.set(apresIdentite);
                    return Mono.empty();
                })).block();

        assertThat(vuParLeService.get().getRequest().getHeaders().get("Idempotency-Key")).containsExactly(CLE);
    }
}
