package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gestionnaire d'erreurs global : chaque type d'exception doit donner le bon statut HTTP et le corps du contrat
 * {code, message, traceId}, sans jamais exposer de détail interne. Tests unitaires, sans contexte Spring ni réseau.
 */
class GlobalErrorHandlerTests {

    /** Simule l'exception de délai de Netty : le gestionnaire la reconnaît par le nom de sa classe. */
    static class ReadTimeoutException extends RuntimeException {
    }

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").build());
    }

    @Test
    void servicePasJoignableDonne503MemeQuandIlEstEnveloppe() {
        assertThat(GlobalErrorHandler.statusFor(new ConnectException("refus"))).isEqualTo(503);
        assertThat(GlobalErrorHandler.statusFor(new UnknownHostException("inconnu"))).isEqualTo(503);
        assertThat(GlobalErrorHandler.statusFor(new RuntimeException("enveloppe", new ConnectException("refus"))))
                .isEqualTo(503);
    }

    @Test
    void serviceTropLentDonne504() {
        assertThat(GlobalErrorHandler.statusFor(new TimeoutException("delai"))).isEqualTo(504);
        assertThat(GlobalErrorHandler.statusFor(new ReadTimeoutException())).isEqualTo(504);
    }

    @Test
    void corpsTropGrosDonne413() {
        assertThat(GlobalErrorHandler.statusFor(new RequestValidationFilter.PayloadTooLarge())).isEqualTo(413);
    }

    @Test
    void statutExplicitDeSpringEstConserve() {
        assertThat(GlobalErrorHandler.statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND))).isEqualTo(404);
        assertThat(GlobalErrorHandler.statusFor(
                new RuntimeException("enveloppe", new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED))))
                .isEqualTo(405);
    }

    @Test
    void erreurInconnueDonne500() {
        assertThat(GlobalErrorHandler.statusFor(new IllegalStateException("bug"))).isEqualTo(500);
    }

    @Test
    void toutesLesCodesEtMessagesDuContratSontDefinis() {
        int[] statuts = {400, 401, 403, 404, 405, 409, 413, 415, 429, 502, 503, 504, 500, 418};
        String[] codes = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "METHOD_NOT_ALLOWED", "CONFLICT",
                "PAYLOAD_TOO_LARGE", "UNSUPPORTED_MEDIA_TYPE", "TOO_MANY_REQUESTS", "BAD_GATEWAY",
                "SERVICE_UNAVAILABLE", "GATEWAY_TIMEOUT", "INTERNAL_SERVER_ERROR", "BAD_REQUEST"};
        for (int i = 0; i < statuts.length; i++) {
            assertThat(GlobalErrorHandler.codeFor(statuts[i])).as("code du statut " + statuts[i]).isEqualTo(codes[i]);
            assertThat(GlobalErrorHandler.messageFor(statuts[i])).as("message du statut " + statuts[i]).isNotBlank();
        }
        assertThat(GlobalErrorHandler.messageFor(500)).isEqualTo("Erreur interne du gateway");
        assertThat(GlobalErrorHandler.messageFor(418)).isEqualTo("Requête invalide");
    }

    @Test
    void handleEcritLaReponseJsonSansDetailInterne() {
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(TraceIdFilter.ATTRIBUTE, "trace-erreur");

        handler.handle(exchange, new IllegalStateException("secret interne")).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(500);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("INTERNAL_SERVER_ERROR").contains("trace-erreur").doesNotContain("secret interne");
    }

    @Test
    void handleRepond503PourUnServiceInjoignable() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new ConnectException("refus")).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("SERVICE_UNAVAILABLE");
    }

    @Test
    void reponseDejaEnvoyeeLErreurEstRelancee() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();   // la réponse est déjà partie : trop tard pour la réécrire
        RuntimeException erreur = new RuntimeException("deja envoyee");

        assertThatThrownBy(() -> handler.handle(exchange, erreur).block()).hasMessage("deja envoyee");
    }
}
