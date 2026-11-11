package com.neobank.gateway.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fabrique les réponses d'erreur au format commun du contrat (§6) :
 * { "code": "...", "message": "...", "traceId": "..." }.
 * Le JSON est construit à la main (avec échappement) pour ne dépendre d'aucune version de bibliothèque JSON.
 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    /** Écrit l'erreur dans la réponse HTTP avec le statut donné. */
    public static Mono<Void> write(ServerWebExchange exchange, int status, String code, String message) {
        byte[] body = json(code, message, traceIdOf(exchange)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Corps JSON du contrat. Public pour pouvoir être testé seul. */
    public static String json(String code, String message, String traceId) {
        return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\",\"traceId\":\"" + escape(traceId) + "\"}";
    }

    /** TraceId de la requête (posé par TraceIdFilter) ; un nouvel UUID en dernier recours. */
    static String traceIdOf(ServerWebExchange exchange) {
        String fromAttribute = exchange.getAttribute(TraceIdFilter.ATTRIBUTE);
        if (fromAttribute != null) {
            return fromAttribute;
        }
        String fromHeader = exchange.getRequest().getHeaders().getFirst(TraceIdFilter.HEADER);
        return fromHeader != null ? fromHeader : UUID.randomUUID().toString();
    }

    /** Échappe guillemets, antislashs et caractères de contrôle pour garder un JSON valide. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
