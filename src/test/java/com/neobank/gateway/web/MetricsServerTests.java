package com.neobank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neobank.gateway.config.GatewayProperties;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Serveur de métriques Prometheus (port interne) : il répond sur /metrics, refuse les méthodes autres que GET,
 * ne démarre pas s'il est désactivé, et n'empêche jamais le gateway de démarrer si son port est pris.
 */
class MetricsServerTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PrometheusMeterRegistry> fournisseur(PrometheusMeterRegistry registre) {
        ObjectProvider<PrometheusMeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registre);
        return provider;
    }

    private static GatewayProperties proprietes(int port) {
        return new GatewayProperties(null, null, null, null, null, new GatewayProperties.Metrics(port));
    }

    private static int portLibre() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpResponse<String> appeler(int port, boolean post) throws Exception {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"));
        requete = post ? requete.POST(HttpRequest.BodyPublishers.noBody()) : requete.GET();
        return HTTP.send(requete.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void exposeLesMetriquesSurLePortInterne() throws Exception {
        PrometheusMeterRegistry registre = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registre.counter("gateway_test_total").increment();
        int port = portLibre();
        MetricsServer serveur = new MetricsServer(proprietes(port), fournisseur(registre));

        serveur.start();
        try {
            assertThat(serveur.isRunning()).isTrue();
            HttpResponse<String> reponse = appeler(port, false);
            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(reponse.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/plain"));
            assertThat(reponse.body()).contains("gateway_test_total");
        } finally {
            serveur.stop();
        }
        assertThat(serveur.isRunning()).isFalse();
    }

    @Test
    void refuseLesMethodesAutresQueGet() throws Exception {
        PrometheusMeterRegistry registre = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        int port = portLibre();
        MetricsServer serveur = new MetricsServer(proprietes(port), fournisseur(registre));

        serveur.start();
        try {
            assertThat(appeler(port, true).statusCode()).isEqualTo(405);
        } finally {
            serveur.stop();
        }
    }

    @Test
    void neDemarrePasSiDesactiveOuSansRegistre() throws Exception {
        PrometheusMeterRegistry registre = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        MetricsServer portZero = new MetricsServer(proprietes(0), fournisseur(registre));
        portZero.start();
        assertThat(portZero.isRunning()).isFalse();

        MetricsServer sansRegistre = new MetricsServer(proprietes(portLibre()), fournisseur(null));
        sansRegistre.start();
        assertThat(sansRegistre.isRunning()).isFalse();

        MetricsServer sansConfig = new MetricsServer(
                new GatewayProperties(null, null, null, null, null, null), fournisseur(registre));
        sansConfig.start();
        assertThat(sansConfig.isRunning()).isFalse();
        sansConfig.stop();   // arrêt sans démarrage : sans effet
    }

    @Test
    void portDejaPrisNeCassePasLeGateway() throws Exception {
        PrometheusMeterRegistry registre = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        int port = portLibre();
        MetricsServer premier = new MetricsServer(proprietes(port), fournisseur(registre));
        MetricsServer second = new MetricsServer(proprietes(port), fournisseur(registre));

        premier.start();
        try {
            second.start();   // même port : l'erreur est journalisée, pas propagée
            assertThat(second.isRunning()).isFalse();
        } finally {
            premier.stop();
        }
    }
}
