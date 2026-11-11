package com.neobank.gateway.web;

import com.neobank.gateway.config.GatewayProperties;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Validation stricte des entrées avant tout traitement (cahier des charges §10) :
 * 1. taille maximale du corps : 413 PAYLOAD_TOO_LARGE (Content-Length vérifié d'avance ; corps "chunked" compté à la volée) ;
 * 2. type de contenu des requêtes avec corps : application/json uniquement (multipart accepté sur /api/kyc/** pour les pièces) : 415 ;
 * 3. format de l'en-tête Idempotency-Key : 8 à 128 caractères parmi A-Z a-z 0-9 . _ : - (un UUID convient) : 400.
 * Le gateway ne lit pas le JSON : le schéma métier (champs, montants, formats) reste validé par chaque service.
 * Ordre : juste après le rate limiting, pour qu'un corps énorme n'atteigne ni la sécurité ni les services.
 */
@Component
public class RequestValidationFilter implements WebFilter, Ordered {

    public static final String IDEMPOTENCY = "Idempotency-Key";
    private static final Pattern IDEMPOTENCY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final GatewayProperties.Validation conf;

    public RequestValidationFilter(GatewayProperties properties) {
        this.conf = properties.validation();
    }

    /** Signale un corps trop gros découvert pendant la lecture (requête "chunked" sans Content-Length). */
    static final class PayloadTooLarge extends RuntimeException {
        PayloadTooLarge() {
            super("payload too large", null, false, false);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (conf == null || !conf.enabled() || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = request.getHeaders();

        // 3. Idempotency-Key : valeur unique et bien formée (sinon injection de caractères dans les logs/clés Redis des services)
        var values = headers.get(IDEMPOTENCY);
        if (values != null) {
            if (values.size() != 1 || !IDEMPOTENCY_FORMAT.matcher(values.get(0)).matches()) {
                return ErrorResponses.write(exchange, 400, "BAD_REQUEST", "En-tête Idempotency-Key invalide");
            }
        }

        HttpMethod method = request.getMethod();
        boolean mayHaveBody = HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method);
        if (!mayHaveBody) {
            return chain.filter(exchange);
        }

        boolean kyc = path.startsWith("/api/kyc/");
        long limit = kyc ? conf.kycMaxBodyBytes() : conf.maxBodyBytes();
        long length = headers.getContentLength();                       // -1 si absent
        boolean chunked = headers.getFirst(HttpHeaders.TRANSFER_ENCODING) != null;

        // 1a. Corps annoncé trop grand : refus immédiat, sans lire un octet
        if (length > limit) {
            return ErrorResponses.write(exchange, 413, "PAYLOAD_TOO_LARGE", "Corps de requête trop volumineux");
        }
        boolean hasBody = length > 0 || chunked;
        // 2. Un corps doit être du JSON (ou du multipart pour le KYC)
        if (hasBody && !contentTypeAllowed(headers.getContentType(), kyc)) {
            return ErrorResponses.write(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "Type de contenu non supporté");
        }
        // 1b. Corps sans taille annoncée : on compte les octets au passage et on coupe au-delà de la limite
        if (chunked && length < 0) {
            // L'erreur PayloadTooLarge remonte jusqu'à GlobalErrorHandler, qui répond 413
            return chain.filter(exchange.mutate().request(limited(request, limit)).build());
        }
        return chain.filter(exchange);
    }

    /** JSON toujours accepté ; multipart/form-data seulement pour le KYC. Les paramètres (charset) sont ignorés. */
    static boolean contentTypeAllowed(MediaType type, boolean kyc) {
        if (type == null) {
            return false;
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(type) && type.getSubtype().equals("json")) {
            return true;
        }
        return kyc && MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type) && type.getSubtype().equals("form-data");
    }

    /** Enveloppe la requête : le flux du corps échoue dès que le total dépasse la limite. */
    private static ServerHttpRequest limited(ServerHttpRequest request, long limit) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                long[] total = {0};
                return super.getBody().<DataBuffer>handle((buffer, sink) -> {
                    total[0] += buffer.readableByteCount();
                    if (total[0] > limit) {
                        DataBufferUtils.release(buffer);
                        sink.error(new PayloadTooLarge());
                    } else {
                        sink.next(buffer);
                    }
                });
            }
        };
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;   // après TraceId (+0), CORS (+2), rate limit IP (+3)
    }
}
