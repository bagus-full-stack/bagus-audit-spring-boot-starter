package io.github.assami.audit.security;

import io.github.assami.audit.properties.AuditProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSecurityConfigTest {

    @Test
    void rolesClaimConverter_prefixesEachRoleWithROLE() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("roles", List.of("AUDIT_READ", "AUDIT_ADMIN")).build();

        var auth = AuditSecurityConfig.rolesClaimConverter(new AuditProperties()).convert(jwt);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_AUDIT_READ", "ROLE_AUDIT_ADMIN");
    }

    @Test
    void rolesClaimConverter_readsTheConfiguredClaim() {
        AuditProperties props = new AuditProperties();
        props.getSecurity().setRolesClaim("groups");
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("groups", List.of("AUDIT_WRITE")).claim("roles", List.of("AUDIT_ADMIN")).build();

        var auth = AuditSecurityConfig.rolesClaimConverter(props).convert(jwt);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUDIT_WRITE");
    }
}
