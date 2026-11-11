package com.neobank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * B9 : le rate limiting fonctionne avec un vrai Redis (Testcontainers).
 * On utilise les routes publiques (login) pour éviter de dépendre d'un JWT / JWKS.
 * Seuil volontairement bas pour que le test soit rapide.
 * Docker doit être démarré avant "mvn verify" (Docker Desktop sous Windows).
 */
// Pas de disabledWithoutDocker : si Docker est arrêté, le test ÉCHOUE au lieu d'être ignoré en silence
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.auth-per-minute=5",
        "gateway.rate-limit.ip-per-second=100",
        "gateway.rate-limit.ip-burst=100",
        // Services aval absents : on s'arrête au rate limit / sécurité
        "gateway.services.identity=http://127.0.0.1:1",
        "gateway.services.banking=http://127.0.0.1:1",
        "gateway.services.payment=http://127.0.0.1:1",
        "gateway.services.notification=http://127.0.0.1:1"
})
class RateLimitIntegrationTests {

    @Container
    static GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            // On attend le message de Redis lui-même, pas seulement l'ouverture du port (le proxy Docker Desktop l'ouvre trop tôt)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void initClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void apresLeSeuilLoginRecoit429AvecRetryAfterEtTraceId() {
        // 5 tentatives autorisées
        for (int i = 0; i < 5; i++) {
            webTestClient.post().uri("/api/auth/login")
                    .exchange()
                    .expectStatus().value(s -> assertThat(s).isNotEqualTo(429));
        }
        // La 6e doit être refusée
        webTestClient.post().uri("/api/auth/login")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void healthNEstJamaisLimite() {
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/actuator/health")
                    .exchange()
                    .expectStatus().isOk();
        }
    }
}
