package com.neobank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import reactor.core.publisher.Mono;

/**
 * B7 : comportement réel du circuit breaker configuré par ResilienceConfig, avec des réglages réduits
 * pour que le test soit rapide (délai 300 ms, circuit jugé sur 4 appels).
 */
@SpringBootTest(properties = {
        "gateway.resilience.timeout-ms=300",
        "gateway.resilience.window-size=4",
        "gateway.resilience.minimum-calls=4",
        "gateway.resilience.failure-rate-percent=50",
        "gateway.resilience.open-seconds=30"})
class ResilienceBehaviourTests {

    @Autowired
    private ReactiveCircuitBreakerFactory<?, ?> factory;

    @Test
    void unAppelQuiDepasseLeDelaiBasculeSurLeSecours() {
        String resultat = factory.create("test-timeout")
                .run(Mono.<String>never(), erreur -> Mono.just("secours"))
                .block(Duration.ofSeconds(5));

        assertThat(resultat).isEqualTo("secours");
    }

    @Test
    void apresPlusieursEchecsLeCircuitSOuvreEtNAppelleDeNouveauPasLeService() {
        var circuit = factory.create("test-ouverture");
        // 4 échecs d'affilée : taux d'échec 100 % sur 4 appels -> le circuit s'ouvre
        for (int i = 0; i < 4; i++) {
            circuit.run(Mono.<String>error(new IllegalStateException("service en panne")),
                    erreur -> Mono.just("secours")).block(Duration.ofSeconds(5));
        }

        // Le 5e appel utiliserait un service qui répondrait bien : on vérifie qu'il n'est même pas sollicité
        AtomicInteger appelsReels = new AtomicInteger();
        String resultat = circuit.run(Mono.defer(() -> {
            appelsReels.incrementAndGet();
            return Mono.just("reponse du service");
        }), erreur -> Mono.just("secours")).block(Duration.ofSeconds(5));

        assertThat(resultat).isEqualTo("secours");
        assertThat(appelsReels.get()).isZero();
    }
}
