package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Agrégation GET /api/dashboard/{accountId} : appels réels (en parallèle) vers de faux services JDK,
 * propagation de l'identité issue du JWT, dégradation gracieuse et 503 quand tout est indisponible.
 */
class DashboardControllerTests {

    private HttpServer services;
    private final AtomicReference<String> userVuParLeSolde = new AtomicReference<>();
    private final AtomicReference<String> roleVuParLeSolde = new AtomicReference<>();
    private final AtomicReference<String> authVuParLeSolde = new AtomicReference<>();
    private volatile int statutOperations = 200;
    private volatile String corpsSolde = "{\"amount\":100}";

    @BeforeEach
    void demarrerLesFauxServices() throws IOException {
        services = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        services.createContext("/accounts/42/balance", ex -> {
            userVuParLeSolde.set(ex.getRequestHeaders().getFirst("X-User-Id"));
            roleVuParLeSolde.set(ex.getRequestHeaders().getFirst("X-User-Role"));
            authVuParLeSolde.set(ex.getRequestHeaders().getFirst("Authorization"));
            repondre(ex, 200, corpsSolde);
        });
        services.createContext("/accounts/42/transactions", ex -> repondre(ex, statutOperations, "[{\"id\":1}]"));
        services.createContext("/notifications/u1", ex -> repondre(ex, 200, "[]"));
        services.start();
    }

    @AfterEach
    void arreterLesFauxServices() {
        services.stop(0);
    }

    private static void repondre(HttpExchange ex, int statut, String corps) throws IOException {
        byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(statut, octets.length);
        ex.getResponseBody().write(octets);
        ex.close();
    }

    private DashboardController controleur() {
        URI base = URI.create("http://127.0.0.1:" + services.getAddress().getPort());
        return new DashboardController(new GatewayProperties(
                new GatewayProperties.Services(base, base, base, base), null, null, null, null, null));
    }

    private static DashboardController controleurSansService() {
        URI mort = URI.create("http://127.0.0.1:1");   // personne n'écoute : connexion refusée
        return new DashboardController(new GatewayProperties(
                new GatewayProperties.Services(mort, mort, mort, mort), null, null, null, null, null));
    }

    /** Requête avec un JWT valide (sub = u1, role = USER) ; l'en-tête X-User-Id du client est un faux. */
    private static ServerWebExchange echangeConnecte() {
        MockServerWebExchange base = MockServerWebExchange.from(MockServerHttpRequest.get("/api/dashboard/42")
                .header("Authorization", "Bearer t")
                .header("X-User-Id", "usurpateur")
                .build());
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u1").claim("role", "USER").build();
        return base.mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();
    }

    @Test
    void troisServicesRepondentLaReponseEstCompleteEtLIdentiteVientDuJwt() {
        ResponseEntity<String> r = controleur().dashboard("42", echangeConnecte()).block();

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"accountId\":\"42\"")
                .contains("\"balance\":{\"amount\":100}")
                .contains("\"transactions\":[{\"id\":1}]")
                .contains("\"notifications\":[]")
                .contains("\"degraded\":[]");
        assertThat(userVuParLeSolde.get()).isEqualTo("u1");          // jamais la valeur envoyée par le client
        assertThat(roleVuParLeSolde.get()).isEqualTo("USER");
        assertThat(authVuParLeSolde.get()).isEqualTo("Bearer t");     // le JWT est transmis au service
    }

    @Test
    void uneOperationEnErreurLaPartieEstNulleEtListeeDansDegraded() {
        statutOperations = 500;

        ResponseEntity<String> r = controleur().dashboard("42", echangeConnecte()).block();

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"transactions\":null").contains("\"degraded\":[\"transactions\"]");
    }

    @Test
    void corpsQuiNEstPasDuJsonEstConsidereEnEchec() {
        corpsSolde = "ceci n'est pas du json";

        ResponseEntity<String> r = controleur().dashboard("42", echangeConnecte()).block();

        assertThat(r.getBody()).contains("\"balance\":null").contains("\"degraded\":[\"balance\"]");
    }

    @Test
    void tousLesServicesInjoignablesDonnent503() {
        ResponseEntity<String> r = controleurSansService().dashboard("42", echangeConnecte()).block();

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("SERVICE_UNAVAILABLE").contains("traceId");
    }

    @Test
    void identifiantDeCompteInvalideDonne400SansAppelAval() {
        ResponseEntity<String> r = controleur().dashboard("../secret", echangeConnecte()).block();

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("BAD_REQUEST");
        assertThat(userVuParLeSolde.get()).isNull();
    }

    @Test
    void sansJwtValideDonne401() {
        ServerWebExchange anonyme = MockServerWebExchange.from(MockServerHttpRequest.get("/api/dashboard/42").build());

        ResponseEntity<String> r = controleur().dashboard("42", anonyme).block();

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("UNAUTHORIZED");
    }
}
