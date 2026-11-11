package com.neobank.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;

/** Vérifie que le claim "role" du JWT devient bien une autorité Spring, et uniquement pour USER / ADMIN. */
class RoleConversionTests {

    private final ReactiveJwtAuthenticationConverter converter = SecurityConfig.jwtAuthenticationConverter();

    /** Construit un JWT minimal avec le rôle donné (null = claim absent). */
    private static Jwt jwtWithRole(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-42");
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }

    private java.util.List<String> authoritiesFor(String role) {
        return converter.convert(jwtWithRole(role)).block().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void roleUserDonneRoleUser() {
        assertThat(authoritiesFor("USER")).containsExactly("ROLE_USER");
    }

    @Test
    void roleAdminDonneRoleAdmin() {
        assertThat(authoritiesFor("ADMIN")).containsExactly("ROLE_ADMIN");
    }

    @Test
    void unRoleInconnuNeDonneAucunDroit() {
        assertThat(authoritiesFor("SUPERUSER")).isEmpty();
    }

    @Test
    void unRoleAbsentNeDonneAucunDroit() {
        assertThat(authoritiesFor(null)).isEmpty();
    }

    @Test
    void lesRolesSontSensiblesALaCasse() {
        // "admin" en minuscules n'est pas le rôle ADMIN du contrat
        assertThat(authoritiesFor("admin")).isEmpty();
    }
}
