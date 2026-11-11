package com.neobank.gateway.web;

import com.neobank.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Agrégation légère de réponses (cahier des charges §6) : GET /api/dashboard/{accountId} réunit en UN appel
 * le solde, les dernières opérations (Core Banking) et les notifications (Notification), que l'écran
 * d'accueil du frontend afficherait sinon avec trois allers-retours.
 *
 * - Les trois appels partent en parallèle, avec le JWT et les en-têtes d'identité de l'utilisateur : chaque
 *   service applique lui-même le contrôle de propriété (un utilisateur ne lit que ses comptes).
 * - Dégradation gracieuse : si une partie échoue, elle vaut null et son nom est listé dans "degraded" ;
 *   si les trois échouent, c'est un 503 comme pour une route normale.
 * - Le gateway ne lit pas le JSON des services : il l'emboîte tel quel (chaque partie est vérifiée comme objet
 *   ou tableau JSON avant insertion, sinon elle est considérée en échec).
 */
@RestController
public class DashboardController {

    private static final Duration PART_TIMEOUT = Duration.ofSeconds(2);   // inférieur au délai global de 3 s
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final String[] FORWARDED = {HttpHeaders.AUTHORIZATION, "X-User-Id", "X-User-Role", TraceIdFilter.HEADER};

    private final WebClient client = WebClient.builder().build();
    private final GatewayProperties.Services services;

    public DashboardController(GatewayProperties properties) {
        this.services = properties.services();
    }

    /** Résultat d'une partie : JSON brut, ou null si indisponible. */
    record Part(String name, String json) {
    }

    @GetMapping(path = "/api/dashboard/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> dashboard(@PathVariable String accountId, ServerWebExchange exchange) {
        // Valide l'identifiant avant de le recopier dans une URL aval (pas d'injection de chemin)
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            return Mono.just(error(exchange, 400, "BAD_REQUEST", "Identifiant de compte invalide"));
        }
        HttpHeaders in = exchange.getRequest().getHeaders();
        // UserHeadersFilter est un filtre de ROUTE : il ne tourne pas pour ce contrôleur.
        // L'identité est donc reprise directement du JWT validé (principal de la requête).
        return exchange.getPrincipal()
                .filter(p -> p instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken)
                .cast(org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken.class)
                .flatMap(jwt -> {
                    String sub = jwt.getToken().getSubject();
                    String role = jwt.getToken().getClaimAsString("role");
                    return Mono.zip(
                            part("balance", services.banking() + "/accounts/" + accountId + "/balance", in, sub, role),
                            part("transactions", services.banking() + "/accounts/" + accountId + "/transactions?limit=5", in, sub, role),
                            part("notifications", services.notification() + "/notifications/" + sub, in, sub, role));
                })
                .map(t -> assemble(exchange, accountId, List.of(t.getT1(), t.getT2(), t.getT3())))
                .switchIfEmpty(Mono.fromSupplier(() ->
                        error(exchange, 401, "UNAUTHORIZED", "Token absent, invalide ou expiré")));
    }

    /** Appelle un service aval ; toute erreur (réseau, délai, statut non 2xx, corps non JSON) donne une partie vide. */
    private Mono<Part> part(String name, String url, HttpHeaders in, String userId, String role) {
        return client.get().uri(url)
                .headers(h -> {
                    for (String header : FORWARDED) {
                        String value = in.getFirst(header);
                        if (value != null) {
                            h.set(header, value);
                        }
                    }
                    h.set("X-User-Id", userId);                       // toujours la valeur du JWT, jamais celle du client
                    if (role != null) {
                        h.set("X-User-Role", role);
                    } else {
                        h.remove("X-User-Role");
                    }
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(PART_TIMEOUT)
                .map(body -> new Part(name, isJson(body) ? body.trim() : null))
                .onErrorResume(e -> Mono.just(new Part(name, null)));
    }

    private static boolean isJson(String body) {
        if (body == null) {
            return false;
        }
        String t = body.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /** Erreur au format commun {code, message, traceId}. */
    private static ResponseEntity<String> error(ServerWebExchange exchange, int status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponses.json(code, message, ErrorResponses.traceIdOf(exchange)));
    }

    /** Construit la réponse : 200 (éventuellement dégradée) ou 503 si plus aucune partie n'est disponible. */
    static ResponseEntity<String> assemble(ServerWebExchange exchange, String accountId, List<Part> parts) {
        List<String> degraded = new ArrayList<>();
        StringBuilder out = new StringBuilder("{\"accountId\":\"").append(accountId).append("\"");
        for (Part part : parts) {
            out.append(",\"").append(part.name()).append("\":").append(part.json() == null ? "null" : part.json());
            if (part.json() == null) {
                degraded.add(part.name());
            }
        }
        out.append(",\"degraded\":[");
        for (int i = 0; i < degraded.size(); i++) {
            out.append(i > 0 ? "," : "").append('"').append(degraded.get(i)).append('"');
        }
        out.append("]}");
        if (degraded.size() == parts.size()) {
            return error(exchange, 503, "SERVICE_UNAVAILABLE", "Service indisponible, réessayez plus tard");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out.toString());
    }
}
