package io.github.assami.audit.security;

import io.github.assami.audit.properties.AuditProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
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

    @Test
    void rolesClaimConverter_readsANestedClaimPath_asKeycloakStoresRealmRoles() {
        AuditProperties props = new AuditProperties();
        props.getSecurity().setRolesClaim("realm_access.roles");
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("realm_access", java.util.Map.of("roles", List.of("AUDIT_READ"))).build();

        var auth = AuditSecurityConfig.rolesClaimConverter(props).convert(jwt);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_AUDIT_READ");
    }

    @Test
    void rolesClaimConverter_exactNameWithDotsWins_andSpaceSeparatedStringsAreSplit() {
        AuditProperties props = new AuditProperties();
        props.getSecurity().setRolesClaim("https://app.example.com/roles");
        Jwt auth0 = Jwt.withTokenValue("t").header("alg", "none")
                .claim("https://app.example.com/roles", List.of("AUDIT_ADMIN")).build();
        props.getSecurity().setRolesClaim("https://app.example.com/roles");

        assertThat(AuditSecurityConfig.rolesClaimConverter(props).convert(auth0).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_AUDIT_ADMIN");

        props.getSecurity().setRolesClaim("scp");
        Jwt spaced = Jwt.withTokenValue("t").header("alg", "none").claim("scp", "AUDIT_READ  AUDIT_WRITE").build();
        assertThat(AuditSecurityConfig.rolesClaimConverter(props).convert(spaced).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_AUDIT_READ", "ROLE_AUDIT_WRITE");
    }

    @Test
    void rolesClaimConverter_missingOrOddClaim_givesNoRoles() {
        AuditProperties props = new AuditProperties();
        Jwt none = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "u").build();
        Jwt odd = Jwt.withTokenValue("t").header("alg", "none").claim("roles", 42).build();

        assertThat(AuditSecurityConfig.rolesClaimConverter(props).convert(none).getAuthorities()).isEmpty();
        assertThat(AuditSecurityConfig.rolesClaimConverter(props).convert(odd).getAuthorities()).isEmpty();
    }

    private static Jwt token(String issuer, Object audience, Instant expires) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(expires.minusSeconds(60)).expiresAt(expires);
        if (issuer != null) b.issuer(issuer);
        if (audience != null) b.claim("aud", audience);
        return b.build();
    }

    private static boolean accepts(AuditProperties props, Jwt jwt) {
        return !AuditSecurityConfig.tokenValidator(props.getSecurity()).validate(jwt).hasErrors();
    }

    @Test
    void validator_withoutIssuerOrAudience_checksOnlyExpiry() {
        AuditProperties props = new AuditProperties();

        assertThat(accepts(props, token("https://anyone", null, Instant.now().plusSeconds(60)))).isTrue();
        assertThat(accepts(props, token(null, null, Instant.now().minusSeconds(3600)))).isFalse();
    }

    @Test
    void validator_rejectsATokenFromAnotherIssuer() {
        AuditProperties props = new AuditProperties();
        props.getSecurity().setIssuer("https://idp.example.com");
        Instant later = Instant.now().plusSeconds(60);

        assertThat(accepts(props, token("https://idp.example.com", null, later))).isTrue();
        assertThat(accepts(props, token("https://evil.example.com", null, later))).isFalse();
        assertThat(accepts(props, token(null, null, later))).isFalse();
    }

    @Test
    void validator_rejectsATokenForAnotherAudience() {
        AuditProperties props = new AuditProperties();
        props.getSecurity().setAudience("audit-api");
        Instant later = Instant.now().plusSeconds(60);

        assertThat(accepts(props, token(null, List.of("audit-api", "other"), later))).isTrue();
        assertThat(accepts(props, token(null, List.of("billing-api"), later))).isFalse();
        assertThat(accepts(props, token(null, null, later))).isFalse();
    }
}
