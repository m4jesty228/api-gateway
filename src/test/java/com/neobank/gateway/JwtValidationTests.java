package com.neobank.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobank.gateway.security.SecurityConfig;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

/**
 * Teste la règle de validation des JWT du contrat (§4) sans démarrer de serveur :
 * on fabrique de vrais tokens RS256 avec une paire de clés générée pour le test.
 */
class JwtValidationTests {

    private static final String ISSUER = "identity-service";

    private static KeyPair identityKeys;   // clés "officielles" d'Identity dans ce test
    private static KeyPair attackerKeys;   // clés d'un attaquant qui forge ses propres tokens
    private static NimbusReactiveJwtDecoder decoder;

    @BeforeAll
    static void preparer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        identityKeys = generator.generateKeyPair();
        attackerKeys = generator.generateKeyPair();

        // Décodeur équivalent à celui de production, mais avec la clé publique fournie directement
        decoder = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) identityKeys.getPublic()).build();
        decoder.setJwtValidator(SecurityConfig.jwtValidator(ISSUER));
    }

    /** Fabrique un token signé RS256 avec la clé privée donnée. */
    private static String token(KeyPair signer, String issuer, Instant expiration) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("0b2f6a4e-1c1d-4f3e-9d57-8a3c1f2e9b10")
                .issuer(issuer)
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expiration))
                .claim("role", "USER")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(signer.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void unTokenValideEstAccepte() throws Exception {
        String valide = token(identityKeys, ISSUER, Instant.now().plusSeconds(900));

        Jwt jwt = decoder.decode(valide).block();

        assertThat(jwt).isNotNull();
        assertThat(jwt.getSubject()).isEqualTo("0b2f6a4e-1c1d-4f3e-9d57-8a3c1f2e9b10");
        assertThat(jwt.<String>getClaim("role")).isEqualTo("USER");
    }

    @Test
    void unTokenExpireEstRefuse() throws Exception {
        // Expiré depuis 10 minutes : bien au-delà de la tolérance d'horloge de 60 s
        String expire = token(identityKeys, ISSUER, Instant.now().minusSeconds(600));

        assertThatThrownBy(() -> decoder.decode(expire).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void unTokenSigneParUnAutreEstRefuse() throws Exception {
        // Token bien formé mais signé avec la clé de l'attaquant : la signature ne correspond pas
        String forge = token(attackerKeys, ISSUER, Instant.now().plusSeconds(900));

        assertThatThrownBy(() -> decoder.decode(forge).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void unTokenDunMauvaisIssuerEstRefuse() throws Exception {
        String mauvaisIssuer = token(identityKeys, "autre-emetteur", Instant.now().plusSeconds(900));

        assertThatThrownBy(() -> decoder.decode(mauvaisIssuer).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void unPayloadModifieApresSignatureEstRefuse() throws Exception {
        String valide = token(identityKeys, ISSUER, Instant.now().plusSeconds(900));
        String[] parts = valide.split("\\.");

        // L'attaquant remplace le role USER par ADMIN, mais ne peut pas refaire la signature
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replaceAll("\"role\"\\s*:\\s*\"USER\"", "\"role\":\"ADMIN\"");
        assertThat(payload).contains("ADMIN");   // garde-fou : la modification a bien eu lieu
        String escalade = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(escalade).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void unTexteQuelconqueEstRefuse() {
        assertThatThrownBy(() -> decoder.decode("abc.def.ghi").block()).isInstanceOf(JwtException.class);
    }
}
