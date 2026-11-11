package com.neobank.gateway.security;

import com.neobank.gateway.config.GatewayProperties;
import com.neobank.gateway.web.ErrorResponses;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Flux;

/**
 * Sécurité du gateway (contrat §3 et §4) : routes publiques, routes réservées aux ADMIN,
 * routes internes bloquées, et JWT RS256 obligatoire pour tout le reste. Les refus (401/403)
 * sont renvoyés au format JSON commun du contrat (§6).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Rôles reconnus (contrat §4) : tout autre valeur dans le claim "role" ne donne aucun droit. */
    public static final Set<String> VALID_ROLES = Set.of("USER", "ADMIN");

    // Opérations internes de Core Banking (contrat §3) : réservées à Payment, jamais exposées aux clients.
    // "/**" couvre aussi le chemin exact et une éventuelle barre oblique finale.
    private static final String[] INTERNAL_PATHS = {"/api/accounts/*/debit/**", "/api/accounts/*/credit/**"};

    /** Chaîne de filtres : qui a le droit d'appeler quoi, et comment on répond aux refus. */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewayProperties properties) {
        // 401 : pas d'identité valide. Le header WWW-Authenticate est exigé par la norme des Bearer tokens
        ServerAuthenticationEntryPoint unauthorized = (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED.value(),
                    "UNAUTHORIZED", "Token absent, invalide ou expiré");
        };
        // 403 : identité valide mais droits insuffisants
        ServerAccessDeniedHandler forbidden = (exchange, ex) -> ErrorResponses.write(exchange,
                HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "Accès refusé");

        List<String> adminPaths = properties.security().adminPaths();

        return http
                // Pas de cookies de session côté gateway : CSRF, login par formulaire et Basic ne servent à rien
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .authorizeExchange(exchanges -> {
                    // 1. Routes internes : bloquées pour tout le monde, administrateurs compris
                    exchanges.pathMatchers(INTERNAL_PATHS).denyAll();
                    // 2. Routes publiques du contrat §3 : inscription, connexion, renouvellement de token
                    exchanges.pathMatchers(HttpMethod.POST,
                            "/api/users/register", "/api/auth/login", "/api/auth/refresh").permitAll();
                    // 3. Santé du gateway (sondes Swarm / Kubernetes)
                    exchanges.pathMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    // 4. Routes de supervision : rôle ADMIN obligatoire (liste configurable)
                    if (!adminPaths.isEmpty()) {
                        exchanges.pathMatchers(adminPaths.toArray(String[]::new)).hasRole("ADMIN");
                    }
                    // 5. Tout le reste : un token valide AVEC un rôle reconnu (USER ou ADMIN)
                    exchanges.anyExchange().hasAnyRole("USER", "ADMIN");
                })
                // Lecture de "Authorization: Bearer ..." ; le rôle est tiré du claim "role" (contrat §4)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Décodeur JWT : signature RS256 vérifiée avec la clé publique récupérée au format JWKS.
     * La clé est téléchargée au premier token reçu (pas au démarrage) : le gateway démarre
     * même si Identity est encore indisponible, et la garde en cache ensuite.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.security().jwksUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)   // refuse tout autre algorithme (ex. HS256, none)
                .build();
        decoder.setJwtValidator(jwtValidator(properties.security().issuer()));
        return decoder;
    }

    /**
     * Règles de validité d'un token : non expiré, pas encore valable si "nbf" est dans le futur,
     * et émis par l'issuer attendu ("identity-service"). Isolée en méthode statique pour être testée seule.
     */
    public static OAuth2TokenValidator<Jwt> jwtValidator(String issuer) {
        return JwtValidators.createDefaultWithIssuer(issuer);
    }

    /**
     * Convertit le claim "role" du JWT (ex. "ADMIN") en autorité Spring "ROLE_ADMIN".
     * Un rôle absent ou inconnu ne produit aucune autorité : l'utilisateur est authentifié
     * mais reçoit 403 sur toutes les routes protégées.
     */
    public static ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authoritiesOf);
        return converter;
    }

    private static Flux<GrantedAuthority> authoritiesOf(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (role == null || !VALID_ROLES.contains(role)) {
            return Flux.empty();
        }
        return Flux.just(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
