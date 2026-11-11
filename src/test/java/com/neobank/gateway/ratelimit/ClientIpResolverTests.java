package com.neobank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Détermination de l'IP du client : l'en-tête X-Forwarded-For ne doit jamais permettre de contourner la limite. */
class ClientIpResolverTests {

    private static MockServerWebExchange requete(String remote, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/accounts/42/balance");
        if (remote != null) {
            builder.remoteAddress(new InetSocketAddress(remote, 40000));
        }
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void sansProxyDeConfianceXForwardedForEstIgnore() {
        // Un attaquant écrit l'en-tête lui-même : on doit garder l'adresse réelle de la connexion
        assertThat(ClientIpResolver.resolve(requete("10.0.0.5", "6.6.6.6"), 0)).isEqualTo("10.0.0.5");
    }

    @Test
    void avecUnProxyDeConfianceOnLitLaDerniereEntreeAjouteeParLeProxy() {
        // "6.6.6.6" est écrit par le client (falsifiable) ; "203.0.113.9" a été ajouté par notre proxy
        assertThat(ClientIpResolver.resolve(requete("10.0.0.5", "6.6.6.6, 203.0.113.9"), 1)).isEqualTo("203.0.113.9");
    }

    @Test
    void sansAdresseDisponibleOnRenvoieUnknown() {
        assertThat(ClientIpResolver.resolve(requete(null, null), 0)).isEqualTo("unknown");
    }
}
