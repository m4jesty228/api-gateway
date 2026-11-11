package com.neobank.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Ajoute Cross-Origin-Resource-Policy sur toutes les réponses : un site tiers ne peut pas inclure nos réponses
 * en mode "no-cors" (protection contre les attaques de type Spectre, alerte ZAP "Site Isolation").
 * Sans effet sur le frontend : les appels fetch() autorisés par CORS ne sont pas soumis à cette politique.
 */
@Component
public class IsolationHeadersFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().getHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;   // posé avant tout filtre qui pourrait répondre (429, 401...)
    }
}
