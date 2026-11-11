package com.neobank.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.UriSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import java.time.Duration;

/**
 * Table de routage du gateway, identique au contrat d'interface (§2).
 * Le préfixe "/api" est retiré avant de transmettre : /api/payments/transfer arrive
 * chez Payment sous la forme /payments/transfer, les services gardent donc leurs chemins d'origine.
 * Les adresses viennent de GatewayProperties (variables d'environnement), jamais écrites en dur ici.
 */
@Configuration
public class RoutesConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties properties) {
        GatewayProperties.Services services = properties.services();

        return builder.routes()
                // Identity & KYC : inscription, authentification, profil, KYC
                .route("identity", r -> r
                        .path("/api/users/**", "/api/auth/**", "/api/kyc/**")
                        .filters(f -> standardFilters(f, "identity"))   // retire "/api" + dédoublonne CORS
                        .uri(services.identity().toString()))
                // Core Banking : comptes, soldes, historique
                .route("banking", r -> r
                        .path("/api/accounts/**")
                        .filters(f -> standardFilters(f, "banking"))
                        .uri(services.banking().toString()))
                // Payment : virements (l'en-tête Idempotency-Key est transmis sans modification)
                .route("payment", r -> r
                        .path("/api/payments/**")
                        .filters(f -> standardFilters(f, "payment"))
                        .uri(services.payment().toString()))
                // Notification : historique des notifications d'un utilisateur
                .route("notification", r -> r
                        .path("/api/notifications/**")
                        .filters(f -> standardFilters(f, "notification"))
                        .uri(services.notification().toString()))
                .build();
    }

    /**
     * Filtres communs aux 4 routes : retrait du préfixe "/api", suppression des doublons d'en-têtes CORS et X-Trace-Id, puis circuit breaker (B7).
     * Le gateway ajoute déjà ses propres en-têtes CORS ; si un service en ajoute aussi, le navigateur
     * rejette la réponse ("multiple values"). Même chose pour X-Trace-Id si un service le renvoie : le client doit
     * en recevoir un seul. On garde la première valeur, celle du gateway.
     */
    private static UriSpec standardFilters(GatewayFilterSpec f, String service) {
        return f.stripPrefix(1)
                .dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials X-Trace-Id", "RETAIN_FIRST")
                // B7 : un circuit breaker par service (le nom = l'identifiant du circuit). Sont comptés comme échecs :
                // service injoignable, délai dépassé (voir ResilienceConfig) et réponses 502/503/504 du service.
                // Les 4xx et les 500 métier (ex. 409 INSUFFICIENT_FUNDS) ne sont jamais touchés : le client les reçoit tels quels.
                .circuitBreaker(config -> config
                        .setName(service)
                        .setFallbackUri("forward:/fallback/" + service)
                        .addStatusCode("502")
                        .addStatusCode("503")
                        .addStatusCode("504"))
                // B15 : retry limité aux lectures (GET), cahier des charges §7 : 2 tentatives de plus au maximum, avec attente
                // croissante (50 ms puis 100 ms). Jamais sur POST/PUT/PATCH/DELETE : rejouer un virement risquerait de le doubler.
                // Placé APRÈS le circuit breaker (donc à l'intérieur) : le délai de 3 s couvre toutes les tentatives, et seul
                // l'échec final compte pour l'ouverture du circuit.
                .retry(retry -> retry
                        .setRetries(2)
                        .setMethods(HttpMethod.GET)
                        .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)
                        .setBackoff(Duration.ofMillis(50), Duration.ofMillis(500), 2, true));
    }
}
