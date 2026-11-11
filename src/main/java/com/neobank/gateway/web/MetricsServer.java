package com.neobank.gateway.web;

import com.neobank.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Expose les métriques Prometheus (cahier des charges §17) sur un port INTERNE distinct (9090 par défaut),
 * jamais publié par la stack Swarm : Prometheus les lit sur le réseau banking-network, les clients n'y accèdent pas.
 * Un second serveur plutôt que /actuator/prometheus sur le port 8080, parce que ce port est ouvert à Internet
 * et que l'exposer demanderait une authentification que Prometheus ne sait pas faire avec un JWT.
 * Désactivé si gateway.metrics.port = 0, ou si aucun registre Prometheus n'est présent.
 */
@Component
public class MetricsServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MetricsServer.class);

    private final int port;
    private final ObjectProvider<PrometheusMeterRegistry> registry;
    private HttpServer server;

    public MetricsServer(GatewayProperties properties, ObjectProvider<PrometheusMeterRegistry> registry) {
        this.port = properties.metrics() == null ? 0 : properties.metrics().port();
        this.registry = registry;
    }

    @Override
    public void start() {
        PrometheusMeterRegistry prometheus = registry.getIfAvailable();
        if (port <= 0 || prometheus == null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/metrics", exchange -> {
                byte[] body = prometheus.scrape().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders("GET".equals(exchange.getRequestMethod()) ? 200 : 405, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "metrics-server");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            log.info("Métriques Prometheus sur le port interne {} (/metrics)", port);
        } catch (IOException e) {
            // Les métriques ne doivent jamais empêcher la banque de fonctionner
            log.warn("Serveur de métriques non démarré sur le port {} : {}", port, e.toString());
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }
}
