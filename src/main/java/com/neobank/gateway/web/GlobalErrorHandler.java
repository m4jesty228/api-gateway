package com.neobank.gateway.web;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Transforme toute erreur du gateway en réponse JSON du contrat (§6), à la place de la page d'erreur
 * par défaut de Spring. Cas visés : route inconnue (404), service aval injoignable (503),
 * service trop lent (504), erreur imprévue (500, sans aucun détail interne).
 * L'ordre -2 le place avant le gestionnaire par défaut de Spring Boot (-1), qui n'est alors plus appelé.
 */
@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);   // trop tard pour réécrire la réponse
        }
        int status = statusFor(error);
        if (status >= 500) {
            // Le détail technique reste dans les logs, jamais dans la réponse envoyée au client
            log.warn("Erreur {} sur {} (traceId={}) : {}", status, exchange.getRequest().getPath(),
                    ErrorResponses.traceIdOf(exchange), error.toString());
        }
        return ErrorResponses.write(exchange, status, codeFor(status), messageFor(status));
    }

    /** Déduit le statut HTTP en parcourant la chaîne des causes de l'exception. */
    static int statusFor(Throwable error) {
        // 1. Problèmes réseau vers le service aval : prioritaires, même si Spring les a enveloppés
        for (Throwable t = error; t != null; t = nextCause(t)) {
            if (t instanceof RequestValidationFilter.PayloadTooLarge) {
                return 413;   // corps "chunked" plus gros que la limite, découvert pendant la lecture
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return 503;   // service arrêté, port fermé ou nom DNS introuvable
            }
            if (t instanceof TimeoutException || t.getClass().getName().endsWith("ReadTimeoutException")) {
                return 504;   // le service n'a pas répondu à temps
            }
        }
        // 2. Statut explicite posé par Spring (ex. 404 route inconnue)
        for (Throwable t = error; t != null; t = nextCause(t)) {
            if (t instanceof ResponseStatusException responseStatus) {
                return responseStatus.getStatusCode().value();
            }
        }
        return 500;
    }

    private static Throwable nextCause(Throwable t) {
        return t.getCause() == t ? null : t.getCause();   // protège d'une cause qui se référence elle-même
    }

    static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "TOO_MANY_REQUESTS";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "GATEWAY_TIMEOUT";
            default -> status >= 500 ? "INTERNAL_SERVER_ERROR" : "BAD_REQUEST";
        };
    }

    static String messageFor(int status) {
        return switch (status) {
            case 401 -> "Token absent, invalide ou expiré";
            case 403 -> "Accès refusé";
            case 404 -> "Route ou ressource inconnue";
            case 405 -> "Méthode non autorisée";
            case 413 -> "Corps de requête trop volumineux";
            case 429 -> "Trop de requêtes, réessayez plus tard";
            case 502 -> "Réponse invalide du service";
            case 503 -> "Service indisponible, réessayez plus tard";
            case 504 -> "Le service n'a pas répondu à temps";
            default -> status >= 500 ? "Erreur interne du gateway" : "Requête invalide";
        };
    }
}
