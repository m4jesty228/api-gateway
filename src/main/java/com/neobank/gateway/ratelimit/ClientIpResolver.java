package com.neobank.gateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

/**
 * Détermine l'IP du client pour le rate limiting.
 * Par défaut (trustedProxies = 0) : l'adresse de la connexion TCP, la seule qu'un client ne peut pas falsifier.
 * Si le gateway est derrière N proxys de confiance (load balancer, Ingress...), on lit X-Forwarded-For en
 * ne faisant confiance qu'aux N dernières entrées : sinon un attaquant changerait l'en-tête à chaque requête
 * pour contourner la limite.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(ServerWebExchange exchange, int trustedProxies) {
        InetSocketAddress address = trustedProxies > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxies).resolve(exchange)
                : exchange.getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return "unknown";
        }
        return address.getAddress().getHostAddress();
    }
}
