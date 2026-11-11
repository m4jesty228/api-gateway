package com.neobank.gateway.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Attribue un X-Trace-Id à chaque requête (contrat §5). S'exécute AVANT la sécurité :
 * même une réponse 401 ou 403 porte donc un traceId, que les services reprendront dans leurs logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = "gateway.traceId";

    // Un traceId fourni par un client n'est accepté que s'il est inoffensif (anti-injection dans les logs)
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));

        exchange.getAttributes().put(ATTRIBUTE, traceId);                    // pour les réponses d'erreur
        exchange.getResponse().getHeaders().set(HEADER, traceId);            // visible par le client
        // Transmis aux services en aval, en remplaçant toute valeur envoyée par le client
        ServerWebExchange forwarded = exchange.mutate()
                .request(request -> request.header(HEADER, traceId))
                .build();
        return chain.filter(forwarded);
    }

    /** Reprend l'identifiant du client s'il est sûr, sinon en génère un nouveau (UUID). */
    public static String resolve(String incoming) {
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
