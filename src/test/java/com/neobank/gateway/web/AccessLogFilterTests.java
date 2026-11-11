package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.neobank.gateway.config.GatewayProperties;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Journal d'accès : champs structurés, traceId, statut déduit des erreurs, aucune donnée sensible. */
class AccessLogFilterTests {

    private final AccessLogFilter filtre = new AccessLogFilter(new GatewayProperties(null, null,
            new GatewayProperties.RateLimit(true, 0, 10, 20, 40, 10, 20), new GatewayProperties.Cors(java.util.List.of("http://x")), null, null));

    private ListAppender<ILoggingEvent> evenements;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void brancherLAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("gateway.access");
        logger.setLevel(Level.INFO);
        evenements = new ListAppender<>();
        evenements.start();
        logger.addAppender(evenements);
    }

    @AfterEach
    void retirerLAppender() {
        logger.detachAppender(evenements);
    }

    private static MockServerWebExchange requete(String url) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(url)
                .remoteAddress(new InetSocketAddress("10.0.0.5", 40000)).build());
        exchange.getAttributes().put(TraceIdFilter.ATTRIBUTE, "trace-test-1234");
        return exchange;
    }

    private static WebFilterChain repond(HttpStatus statut) {
        return e -> {
            e.getResponse().setStatusCode(statut);
            return Mono.empty();
        };
    }

    @Test
    void uneLigneStructureeParRequeteAvecLeTraceId() {
        filtre.filter(requete("/api/accounts/42/balance"), repond(HttpStatus.OK)).block();

        assertThat(evenements.list).hasSize(1);
        ILoggingEvent ligne = evenements.list.get(0);
        assertThat(ligne.getLevel()).isEqualTo(Level.INFO);
        assertThat(ligne.getMDCPropertyMap())
                .containsEntry("traceId", "trace-test-1234")
                .containsEntry("method", "GET")
                .containsEntry("path", "/api/accounts/42/balance")
                .containsEntry("status", "200")
                .containsEntry("clientIp", "10.0.0.5")
                .containsEntry("route", "-")
                .containsKey("durationMs");
    }

    @Test
    void laQueryStringNEstJamaisJournalisee() {
        filtre.filter(requete("/api/accounts/42/balance?token=SECRET123&password=abc"), repond(HttpStatus.OK)).block();

        ILoggingEvent ligne = evenements.list.get(0);
        assertThat(ligne.getMDCPropertyMap().get("path")).isEqualTo("/api/accounts/42/balance");
        assertThat(ligne.getFormattedMessage()).doesNotContain("SECRET123");
        assertThat(ligne.getMDCPropertyMap().values()).noneMatch(valeur -> valeur.contains("SECRET123"));
    }

    @Test
    void lesSondesDeSanteNeSontPasJournalisees() {
        filtre.filter(requete("/actuator/health/readiness"), repond(HttpStatus.OK)).block();

        assertThat(evenements.list).isEmpty();
    }

    @Test
    void uneErreurServeurEstJournaliseeEnWarnAvecLeStatutDeduitDeLException() {
        WebFilterChain enPanne = e -> Mono.error(new ConnectException("Connection refused"));

        filtre.filter(requete("/api/payments/transfer"), enPanne).onErrorResume(erreur -> Mono.empty()).block();

        assertThat(evenements.list).hasSize(1);
        ILoggingEvent ligne = evenements.list.get(0);
        assertThat(ligne.getLevel()).isEqualTo(Level.WARN);
        assertThat(ligne.getMDCPropertyMap()).containsEntry("status", "503");
    }

    @Test
    void leMdcEstVidePresChaqueLigneEtNeFuitePasVersLaRequeteSuivante() {
        filtre.filter(requete("/api/accounts/42/balance"), repond(HttpStatus.OK)).block();

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
