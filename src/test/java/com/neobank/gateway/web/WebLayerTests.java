package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.security.UserHeadersFilter;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Tests de la couche web du gateway : traceId, en-têtes d'identité, format des erreurs. */
class WebLayerTests {

    // ------------------------------------------------------------------ TraceIdFilter

    @Test
    void unTraceIdValideDuClientEstConserve() {
        assertThat(TraceIdFilter.resolve("abc-12345678")).isEqualTo("abc-12345678");
    }

    @Test
    void unTraceIdDangereuxOuAbsentEstRemplaceParUnUuid() {
        // Retour à la ligne + guillemets : tentative d'injection dans les logs
        String remplace = TraceIdFilter.resolve("x\nFAKE LOG \"entry\"");
        assertThat(remplace).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(TraceIdFilter.resolve(null)).hasSize(36);
        assertThat(TraceIdFilter.resolve("court")).hasSize(36);   // trop court : refusé
    }

    @Test
    void leFiltreAjouteLeTraceIdALaReponseEtALaRequeteTransmise() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").build());
        AtomicReference<ServerWebExchange> transmis = new AtomicReference<>();

        new TraceIdFilter().filter(exchange, e -> {
            transmis.set(e);
            return Mono.empty();
        }).block();

        String traceId = exchange.getResponse().getHeaders().getFirst(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        // Le même identifiant est envoyé au service en aval
        assertThat(transmis.get().getRequest().getHeaders().getFirst(TraceIdFilter.HEADER)).isEqualTo(traceId);
    }

    // ------------------------------------------------------------------ UserHeadersFilter

    private static ServerWebExchange requeteAvecEnTetesUsurpes() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts/1")
                .header(UserHeadersFilter.USER_ROLE, "ADMIN")      // usurpation
                .header(UserHeadersFilter.USER_ID, "pirate")       // usurpation
                .build());
    }

    @Test
    void lesEnTetesDIdentiteViennentDuJwtEtPasDuClient() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-42").claim("role", "USER").build();
        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        AtomicReference<ServerWebExchange> transmis = new AtomicReference<>();
        GatewayFilterChain chaine = e -> {
            transmis.set(e);
            return Mono.empty();
        };

        new UserHeadersFilter().filter(requeteAvecEnTetesUsurpes(), chaine)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertThat(transmis.get().getRequest().getHeaders().getFirst(UserHeadersFilter.USER_ROLE)).isEqualTo("USER");
        assertThat(transmis.get().getRequest().getHeaders().getFirst(UserHeadersFilter.USER_ID)).isEqualTo("user-42");
    }

    @Test
    void sansIdentiteLesEnTetesUsurpesSontSupprimes() {
        AtomicReference<ServerWebExchange> transmis = new AtomicReference<>();
        GatewayFilterChain chaine = e -> {
            transmis.set(e);
            return Mono.empty();
        };

        new UserHeadersFilter().filter(requeteAvecEnTetesUsurpes(), chaine).block();

        assertThat(transmis.get().getRequest().getHeaders().getFirst(UserHeadersFilter.USER_ROLE)).isNull();
        assertThat(transmis.get().getRequest().getHeaders().getFirst(UserHeadersFilter.USER_ID)).isNull();
    }

    // ------------------------------------------------------------------ GlobalErrorHandler

    @Test
    void unServiceInjoignableDonne503() {
        assertThat(GlobalErrorHandler.statusFor(new ConnectException("Connection refused"))).isEqualTo(503);
        assertThat(GlobalErrorHandler.statusFor(new UnknownHostException("payment-service"))).isEqualTo(503);
    }

    @Test
    void uneErreurReseauEnvelopeeDonneAussi503() {
        // Spring enveloppe souvent l'erreur réseau dans une autre exception
        Throwable enveloppee = new IllegalStateException("echec", new ConnectException("refused"));
        assertThat(GlobalErrorHandler.statusFor(enveloppee)).isEqualTo(503);
    }

    @Test
    void unDelaiDepasseDonne504() {
        assertThat(GlobalErrorHandler.statusFor(new TimeoutException("lent"))).isEqualTo(504);
    }

    @Test
    void unStatutExplicitDeSpringEstConserve() {
        assertThat(GlobalErrorHandler.statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND))).isEqualTo(404);
    }

    @Test
    void uneErreurInconnueDonne500() {
        assertThat(GlobalErrorHandler.statusFor(new IllegalArgumentException("bug"))).isEqualTo(500);
    }

    @Test
    void laReponseDErreurRespecteLeFormatDuContrat() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/register").build());

        new GlobalErrorHandler().handle(exchange, new ConnectException("Connection refused")).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        String corps = exchange.getResponse().getBodyAsString().block();
        assertThat(corps).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("\"traceId\":\"");
        // Aucun détail technique ne doit fuiter vers le client
        assertThat(corps).doesNotContain("Connection refused");
    }

    @Test
    void leJsonEchappeLesCaracteresSpeciaux() {
        String json = ErrorResponses.json("CODE", "dit \"bonjour\"\net \\ fin", "id-1");
        assertThat(json).isEqualTo("{\"code\":\"CODE\",\"message\":\"dit \\\"bonjour\\\"\\net \\\\ fin\",\"traceId\":\"id-1\"}");
    }
}
