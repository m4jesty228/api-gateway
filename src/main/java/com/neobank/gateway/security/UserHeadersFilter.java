package com.neobank.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Transmet l'identité de l'utilisateur aux services (contrat §5) : X-User-Id et X-User-Role sont
 * calculés à partir du JWT déjà validé. Les valeurs envoyées par le client sont TOUJOURS supprimées
 * d'abord, sinon un attaquant pourrait se faire passer pour un administrateur.
 */
@Component
public class UserHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken())
                .map(jwt -> forward(exchange, jwt))
                // Route publique (ou pas d'identité) : on nettoie simplement les en-têtes usurpés
                .switchIfEmpty(Mono.fromSupplier(() -> forward(exchange, null)))
                .flatMap(chain::filter);
    }

    /** Retire les en-têtes d'identité du client puis, si on a un JWT valide, pose les vrais. */
    private static ServerWebExchange forward(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(USER_ROLE);
            if (jwt != null) {
                if (jwt.getSubject() != null) {
                    headers.set(USER_ID, jwt.getSubject());
                }
                String role = jwt.getClaimAsString("role");
                if (role != null && SecurityConfig.VALID_ROLES.contains(role)) {
                    headers.set(USER_ROLE, role);
                }
            }
        })).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;   // avant le routage, pour que les services reçoivent les en-têtes
    }
}
