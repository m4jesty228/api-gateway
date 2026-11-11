package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Validation des entrées : taille, type de contenu, Idempotency-Key. Aucun serveur nécessaire. */
class RequestValidationFilterTests {

    private static final long MAX = 100;        // limites minuscules pour tester sans gros corps
    private static final long MAX_KYC = 1000;

    private final AtomicBoolean suite = new AtomicBoolean(false);
    private final WebFilterChain chaine = exchange -> {
        suite.set(true);
        return Mono.empty();
    };

    private static RequestValidationFilter filtre(boolean actif) {
        return new RequestValidationFilter(new GatewayProperties(null, null, null, null,
                new GatewayProperties.Validation(actif, MAX, MAX_KYC), null));
    }

    private static MockServerWebExchange exchange(HttpMethod m, String path, MediaType type, long longueur, String idem) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.method(m, path);
        if (type != null) {
            b.header("Content-Type", type.toString());
        }
        if (longueur >= 0) {
            b.header("Content-Length", String.valueOf(longueur));
        }
        if (idem != null) {
            b.header("Idempotency-Key", idem);
        }
        return MockServerWebExchange.from(b.build());
    }

    private int statut(MockServerWebExchange e) {
        return e.getResponse().getStatusCode() == null ? 200 : e.getResponse().getStatusCode().value();
    }

    @Test
    void unPostJsonDansLaLimitePasse() {
        MockServerWebExchange e = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.APPLICATION_JSON, 50, null);
        filtre(true).filter(e, chaine).block();
        assertThat(suite).isTrue();
    }

    @Test
    void unCorpsAnnonceTropGrandRecoitUn413SansAtteindreLeService() {
        MockServerWebExchange e = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.APPLICATION_JSON, MAX + 1, null);
        filtre(true).filter(e, chaine).block();
        assertThat(suite).isFalse();
        assertThat(statut(e)).isEqualTo(413);
        assertThat(e.getResponse().getBodyAsString().block()).contains("PAYLOAD_TOO_LARGE").contains("traceId");
    }

    @Test
    void leKycAccepteUnCorpsPlusGrandEtDuMultipart() {
        MockServerWebExchange e = exchange(HttpMethod.POST, "/api/kyc/submit", MediaType.MULTIPART_FORM_DATA, 500, null);
        filtre(true).filter(e, chaine).block();
        assertThat(suite).isTrue();
    }

    @Test
    void lMultipartEstRefuseHorsKycEtUnTypeInconnuAussi() {
        MockServerWebExchange e1 = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.MULTIPART_FORM_DATA, 10, null);
        filtre(true).filter(e1, chaine).block();
        MockServerWebExchange e2 = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.TEXT_PLAIN, 10, null);
        filtre(true).filter(e2, chaine).block();
        MockServerWebExchange e3 = exchange(HttpMethod.POST, "/api/payments/transfer", null, 10, null);
        filtre(true).filter(e3, chaine).block();
        assertThat(suite).isFalse();
        assertThat(statut(e1)).isEqualTo(415);
        assertThat(statut(e2)).isEqualTo(415);
        assertThat(statut(e3)).isEqualTo(415);
    }

    @Test
    void unGetOuUnPostSansCorpsNeDemandePasDeContentType() {
        filtre(true).filter(exchange(HttpMethod.GET, "/api/accounts/1/balance", null, -1, null), chaine).block();
        assertThat(suite).isTrue();
        suite.set(false);
        filtre(true).filter(exchange(HttpMethod.POST, "/api/accounts/1/unblock", null, 0, null), chaine).block();
        assertThat(suite).isTrue();
    }

    @Test
    void idempotencyKeyMalFormeeRecoitUn400() {
        for (String mauvaise : List.of("court", "avec espace dedans 123", "a".repeat(129), "cle\u0000nulle12345")) {
            suite.set(false);
            MockServerWebExchange e = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.APPLICATION_JSON, 10, mauvaise);
            filtre(true).filter(e, chaine).block();
            assertThat(suite).as(mauvaise).isFalse();
            assertThat(statut(e)).isEqualTo(400);
        }
    }

    @Test
    void unUuidEstUneIdempotencyKeyValide() {
        MockServerWebExchange e = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.APPLICATION_JSON, 10,
                "9f1c2b7e-3a44-4d0e-8b19-6f2a5c7d1e30");
        filtre(true).filter(e, chaine).block();
        assertThat(suite).isTrue();
    }

    @Test
    void unCorpsChunkedQuiDepasseLaLimiteFaitEchouerLeFlux() {
        MockServerHttpRequest requete = MockServerHttpRequest.post("/api/payments/transfer")
                .contentType(MediaType.APPLICATION_JSON).header("Transfer-Encoding", "chunked")
                .body(Flux.just(new DefaultDataBufferFactory().wrap("x".repeat((int) MAX + 10).getBytes(StandardCharsets.UTF_8))));
        MockServerWebExchange e = MockServerWebExchange.from(requete);

        // La chaîne lit le corps, comme le fait le proxy : l'erreur doit être celle du filtre (convertie en 413 par GlobalErrorHandler)
        WebFilterChain lecteur = ex -> ex.getRequest().getBody().then();
        try {
            filtre(true).filter(e, lecteur).block();
            org.junit.jupiter.api.Assertions.fail("Une erreur PayloadTooLarge était attendue");
        } catch (RuntimeException erreur) {
            assertThat(GlobalErrorHandler.statusFor(erreur)).isEqualTo(413);
        }
    }

    @Test
    void desactiveLeFiltreNeToucheARien() {
        MockServerWebExchange e = exchange(HttpMethod.POST, "/api/payments/transfer", MediaType.TEXT_PLAIN, 99999, "x");
        filtre(false).filter(e, chaine).block();
        assertThat(suite).isTrue();
    }

    @Test
    void lesSondesDeSanteNeSontJamaisFiltrees() {
        MockServerWebExchange e = exchange(HttpMethod.GET, "/actuator/health/liveness", null, -1, "x");
        filtre(true).filter(e, chaine).block();
        assertThat(suite).isTrue();
    }
}
