package com.neobank.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Réponse de secours du circuit breaker (B7). Le gateway y aboutit par "forward:/fallback/{service}" quand
 * un service est injoignable, trop lent, répond 502/503/504, ou que son circuit est ouvert.
 * Le client reçoit toujours le même 503 structuré {code, message, traceId} du contrat, sans détail interne.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    // Méthodes listées explicitement (règle Sonar S3752) : le forward du circuit breaker conserve la méthode de la requête
    @RequestMapping(path = "/fallback/{service}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<Void> fallback(@PathVariable String service, ServerWebExchange exchange) {
        // Le nom vient de l'URL : on ne garde que des lettres minuscules et des tirets avant de le journaliser
        String safeName = service.replaceAll("[^a-z-]", "?");
        log.warn("Service {} indisponible (traceId={})", safeName, ErrorResponses.traceIdOf(exchange));
        return ErrorResponses.write(exchange, 503, "SERVICE_UNAVAILABLE", "Service indisponible, réessayez plus tard");
    }
}
