package com.neobank.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de résilience (préfixe "gateway.resilience", B7), tous modifiables par variable d'environnement.
 * Volontairement séparés de GatewayProperties pour ne pas toucher aux tests des blocs précédents.
 *
 * @param timeoutMs         délai maximal d'un appel aval, en millisecondes (contrat §7 : 2 à 3 s)
 * @param windowSize        nombre d'appels récents sur lesquels le circuit calcule son taux d'échec
 * @param minimumCalls      nombre d'appels minimum avant toute décision (évite d'ouvrir sur 1 seul échec)
 * @param failureRatePercent pourcentage d'échecs qui ouvre le circuit
 * @param openSeconds       durée d'ouverture : pendant ce temps, réponse 503 immédiate sans appeler le service
 * @param halfOpenCalls     appels d'essai autorisés ensuite ; s'ils réussissent, le circuit se referme
 */
@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(int timeoutMs, int windowSize, int minimumCalls,
                                   int failureRatePercent, int openSeconds, int halfOpenCalls) {
}
