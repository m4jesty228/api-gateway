package com.neobank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Vérifie que le contexte démarre et que la configuration externalisée est bien chargée. */
@SpringBootTest
class ApiGatewayApplicationTests {

    @Autowired
    private GatewayProperties properties;

    @Test
    void leContexteDemarre() {
        // Si le contexte ne démarre pas (mauvaise version Spring Cloud, etc.), ce test échoue
        assertThat(properties).isNotNull();
    }

    @Test
    void lesAdressesParDefautCorrespondentAuContrat() {
        // Valeurs par défaut de application.yml (hors variables d'environnement)
        assertThat(properties.services().identity()).hasToString("http://localhost:8081");
        assertThat(properties.services().banking()).hasToString("http://localhost:8082");
        assertThat(properties.services().payment()).hasToString("http://localhost:8083");
        assertThat(properties.services().notification()).hasToString("http://localhost:8084");
        // Paramètres JWT par défaut (contrat §4)
        assertThat(properties.security().issuer()).isEqualTo("identity-service");
        assertThat(properties.security().jwksUri())
                .hasToString("http://localhost:8081/.well-known/jwks.json");
        assertThat(properties.security().adminPaths()).containsExactly("/api/accounts/*/unblock");
    }

    @Test
    void lesValeursParDefautDuRateLimitingEtDuCorsSontChargees() {
        // B5 : limites par défaut (application.yml, hors variables d'environnement)
        assertThat(properties.rateLimit().enabled()).isTrue();
        assertThat(properties.rateLimit().trustedProxies()).isZero();
        assertThat(properties.rateLimit().authPerMinute()).isEqualTo(10);
        assertThat(properties.rateLimit().ipPerSecond()).isEqualTo(20);
        assertThat(properties.rateLimit().ipBurst()).isEqualTo(40);
        assertThat(properties.cors().allowedOrigins())
                .containsExactly("http://localhost:3000", "http://localhost:5173");
    }
}
