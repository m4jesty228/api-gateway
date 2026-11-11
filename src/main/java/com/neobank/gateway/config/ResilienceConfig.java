package com.neobank.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Règles du circuit breaker, appliquées à chacun des circuits (identity, banking, payment, notification).
 * Un circuit par service : la panne de Notification ne doit jamais bloquer les virements (exigence de disponibilité).
 *
 * Cycle de vie d'un circuit :
 *   FERMÉ    -> les appels passent ; on surveille le taux d'échec sur les derniers appels
 *   OUVERT   -> trop d'échecs : réponse 503 immédiate (fail-fast), le service est laissé tranquille
 *   SEMI-OUVERT -> après un délai, quelques appels d'essai ; succès = FERMÉ, échec = OUVERT à nouveau
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerDefaults(ResilienceProperties p) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(circuitBreakerConfig(p))
                .timeLimiterConfig(timeLimiterConfig(p))
                .build());
    }

    /** Public et statique pour pouvoir être vérifié seul dans les tests. */
    public static CircuitBreakerConfig circuitBreakerConfig(ResilienceProperties p) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.windowSize())
                .minimumNumberOfCalls(p.minimumCalls())
                .failureRateThreshold(p.failureRatePercent())
                .waitDurationInOpenState(Duration.ofSeconds(p.openSeconds()))
                .permittedNumberOfCallsInHalfOpenState(p.halfOpenCalls())
                // Passage OUVERT -> SEMI-OUVERT sans attendre qu'un appel arrive
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    /**
     * Délai maximal par appel. Sans cette configuration, Resilience4j applique 1 seconde, ce qui ferait
     * échouer des appels légitimes. Un dépassement compte comme un échec pour le circuit.
     */
    public static TimeLimiterConfig timeLimiterConfig(ResilienceProperties p) {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(p.timeoutMs()))
                .cancelRunningFuture(true)
                .build();
    }
}
