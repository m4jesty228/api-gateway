package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Assemblage de l'agrégation : réponse complète, dégradée, ou 503 quand plus rien n'est disponible. */
class DashboardAssemblyTests {

    private static final MockServerWebExchange ECHANGE =
            MockServerWebExchange.from(MockServerHttpRequest.get("/api/dashboard/42").build());

    @Test
    void tousLesServicesRepondentLeJsonEstEmboiteTelQuel() {
        ResponseEntity<String> r = DashboardController.assemble(ECHANGE, "42", List.of(
                new DashboardController.Part("balance", "{\"amount\":100}"),
                new DashboardController.Part("transactions", "[{\"id\":1}]"),
                new DashboardController.Part("notifications", "[]")));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo(
                "{\"accountId\":\"42\",\"balance\":{\"amount\":100},\"transactions\":[{\"id\":1}],\"notifications\":[],\"degraded\":[]}");
    }

    @Test
    void unePartieEnEchecDonneNullEtEstListeeDansDegraded() {
        ResponseEntity<String> r = DashboardController.assemble(ECHANGE, "42", List.of(
                new DashboardController.Part("balance", "{\"amount\":100}"),
                new DashboardController.Part("transactions", null),
                new DashboardController.Part("notifications", "[]")));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"transactions\":null").contains("\"degraded\":[\"transactions\"]");
    }

    @Test
    void toutEnEchecDonneUn503AuFormatCommun() {
        ResponseEntity<String> r = DashboardController.assemble(ECHANGE, "42", List.of(
                new DashboardController.Part("balance", null),
                new DashboardController.Part("transactions", null),
                new DashboardController.Part("notifications", null)));

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("SERVICE_UNAVAILABLE").contains("traceId");
    }
}
