package com.neobank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.gateway.config.ResilienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** B7 : valeurs par défaut de la résilience (application.yml, hors variables d'environnement). */
@SpringBootTest
class ResiliencePropertiesTests {

    @Autowired
    private ResilienceProperties properties;

    @Test
    void lesValeursParDefautRespectentLeContrat() {
        assertThat(properties.timeoutMs()).isBetween(2000, 3000);   // contrat §7 : 2 à 3 s
        assertThat(properties.windowSize()).isEqualTo(10);
        assertThat(properties.minimumCalls()).isEqualTo(5);
        assertThat(properties.failureRatePercent()).isEqualTo(50);
        assertThat(properties.openSeconds()).isEqualTo(10);
        assertThat(properties.halfOpenCalls()).isEqualTo(3);
    }
}
