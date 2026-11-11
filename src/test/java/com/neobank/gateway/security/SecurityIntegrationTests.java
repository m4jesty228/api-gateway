package com.neobank.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.junit.jupiter.api.BeforeEach;

/**
 * B9 : tests d'intégration HTTP de la sécurité (401 / 403 / 200).
 * Le décodeur JWT de production (JWKS réseau) est remplacé par une clé RSA locale connue,
 * ce qui permet de fabriquer des tokens valides, expirés, forgés ou au mauvais rôle
 * sans démarrer Identity ni MockServices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Pas de Redis réel ici : le rate limiting est désactivé pour isoler la sécurité
        "gateway.rate-limit.enabled=false",
        // Routes admin connues
        "gateway.security.admin-paths=/api/accounts/*/unblock",
        // Services aval inexistants : on ne teste que la couche sécurité (avant le proxy)
        "gateway.services.identity=http://127.0.0.1:1",
        "gateway.services.banking=http://127.0.0.1:1",
        "gateway.services.payment=http://127.0.0.1:1",
        "gateway.services.notification=http://127.0.0.1:1"
})
class SecurityIntegrationTests {

    private static final String ISSUER = "identity-service";
    private static KeyPair identityKeys;
    private static KeyPair attackerKeys;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void initClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @BeforeAll
    static void genererCles() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        identityKeys = generator.generateKeyPair();
        attackerKeys = generator.generateKeyPair();
    }

    /** Token RS256 signé avec les claims du contrat (§4). */
    private static String token(KeyPair signer, String role, Instant expiration) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-42")
                .issuer(ISSUER)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiration))
                .claim("role", role)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(signer.getPrivate()));
        return jwt.serialize();
    }

    private static String userToken() throws Exception {
        return token(identityKeys, "USER", Instant.now().plusSeconds(900));
    }

    private static String adminToken() throws Exception {
        return token(identityKeys, "ADMIN", Instant.now().plusSeconds(900));
    }

    // ------------------------------------------------------------------ Routes publiques

    @Test
    void loginSansTokenEstAccepte() {
        webTestClient.post().uri("/api/auth/login")
                .exchange()
                // 503 car le faux service Identity n'est pas là, mais PAS 401
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401).isNotEqualTo(403));
    }

    @Test
    void healthSansTokenEstAccepte() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // ------------------------------------------------------------------ 401

    @Test
    void sansTokenSurRouteProtegee_401() {
        webTestClient.get().uri("/api/accounts/42/balance")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void tokenExpire_401() throws Exception {
        String expire = token(identityKeys, "USER", Instant.now().minusSeconds(600));
        webTestClient.get().uri("/api/accounts/42/balance")
                .header("Authorization", "Bearer " + expire)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void tokenForgeParAutreCle_401() throws Exception {
        String forge = token(attackerKeys, "ADMIN", Instant.now().plusSeconds(900));
        webTestClient.get().uri("/api/accounts/42/balance")
                .header("Authorization", "Bearer " + forge)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void tokenMalForme_401() {
        webTestClient.get().uri("/api/accounts/42/balance")
                .header("Authorization", "Bearer not.a.jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------ 403

    @Test
    void userSurRouteAdmin_403() throws Exception {
        webTestClient.post().uri("/api/accounts/42/unblock")
                .header("Authorization", "Bearer " + userToken())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void roleInconnu_403() throws Exception {
        // Token authentifié mais sans autorité reconnue → 403 sur toute route protégée
        String inconnu = token(identityKeys, "SUPERUSER", Instant.now().plusSeconds(900));
        webTestClient.get().uri("/api/accounts/42/balance")
                .header("Authorization", "Bearer " + inconnu)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void routesInternesDebitCreditToujoursRefusees_403Ou401() throws Exception {
        // Même un ADMIN ne peut pas appeler debit/credit (contrat §3)
        webTestClient.post().uri("/api/accounts/42/debit")
                .header("Authorization", "Bearer " + adminToken())
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(HttpStatus.FORBIDDEN.value(), HttpStatus.UNAUTHORIZED.value(), 403));
    }

    // ------------------------------------------------------------------ 200 / passage après auth

    @Test
    void userSurRouteNormalePasseLaSecurite() throws Exception {
        // La sécurité laisse passer ; le proxy échoue ensuite (service down) → 503, pas 401/403
        webTestClient.get().uri("/api/accounts/42/balance")
                .header("Authorization", "Bearer " + userToken())
                .exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401).isNotEqualTo(403));
    }

    @Test
    void adminSurRouteAdminPasseLaSecurite() throws Exception {
        webTestClient.post().uri("/api/accounts/42/unblock")
                .header("Authorization", "Bearer " + adminToken())
                .exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401).isNotEqualTo(403));
    }

    /**
     * Remplace le décodeur JWKS réseau par un décodeur local basé sur la clé publique
     * générée dans @BeforeAll. Ainsi les tokens signés dans les tests sont acceptés.
     */
    @TestConfiguration
    static class LocalJwtConfig {

        @Bean
        @Primary
        ReactiveJwtDecoder localJwtDecoder() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withPublicKey((RSAPublicKey) identityKeys.getPublic())
                    .build();
            decoder.setJwtValidator(SecurityConfig.jwtValidator(ISSUER));
            return decoder;
        }
    }
}
