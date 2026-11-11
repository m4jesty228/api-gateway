package com.neobank.gateway.web;

import com.neobank.gateway.config.GatewayProperties;
import com.neobank.gateway.ratelimit.ClientIpResolver;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Journal d'accès : une ligne structurée par requête (contrat §5, observabilité §17), avec le traceId qui permet
 * de retrouver la même requête dans les logs de tous les services. Placé juste après TraceIdFilter, il enveloppe
 * tout le reste : les réponses 401, 403, 429 et 5xx sont donc journalisées elles aussi.
 *
 * Les champs sont posés dans le MDC le temps d'un seul appel de log, puis retirés : en programmation réactive
 * un MDC "global" fuiterait d'une requête à l'autre car les threads sont partagés.
 *
 * Ce qui n'est JAMAIS journalisé : l'en-tête Authorization, les en-têtes d'identité, le corps, et la query string
 * (elle peut contenir des secrets). Seul le chemin est conservé.
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger("gateway.access");

    private final int trustedProxies;

    public AccessLogFilter(GatewayProperties properties) {
        this.trustedProxies = properties.rateLimit().trustedProxies();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // Les sondes de santé (toutes les quelques secondes sous Swarm) noieraient les vraies requêtes
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return chain.filter(exchange);
        }

        long start = System.nanoTime();
        AtomicInteger errorStatus = new AtomicInteger(0);

        return chain.filter(exchange)
                // Une exception n'a pas encore de statut HTTP à ce stade (GlobalErrorHandler l'écrira après nous) :
                // on déduit le même statut que lui pour que le journal dise 503 et non "inconnu"
                .doOnError(error -> errorStatus.set(GlobalErrorHandler.statusFor(error)))
                .doFinally(signal -> record(exchange, path, start, errorStatus.get()));
    }

    private void record(ServerWebExchange exchange, String path, long start, int errorStatus) {
        HttpStatusCode code = exchange.getResponse().getStatusCode();
        int status = code != null ? code.value() : errorStatus;
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String method = String.valueOf(exchange.getRequest().getMethod());
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String traceId = exchange.getAttribute(TraceIdFilter.ATTRIBUTE);

        try {
            MDC.put("traceId", traceId != null ? traceId : "-");
            MDC.put("method", method);
            MDC.put("path", path);
            MDC.put("status", String.valueOf(status));
            MDC.put("durationMs", String.valueOf(durationMs));
            MDC.put("clientIp", ClientIpResolver.resolve(exchange, trustedProxies));
            MDC.put("route", route != null ? route.getId() : "-");
            if (status >= 500) {
                log.warn("{} {} -> {} ({} ms)", method, path, status, durationMs);
            } else {
                log.info("{} {} -> {} ({} ms)", method, path, status, durationMs);
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;   // après TraceIdFilter (+0), avant CORS (+2) et le rate limiting (+3)
    }
}
