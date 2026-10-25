package io.github.assami.audit.security;

import io.github.assami.audit.properties.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Default security configuration for the audit endpoints.
 * <p>
 * Protected by role-based access control:
 * <ul>
 *   <li>GET  — {@code ROLE_AUDIT_READ} or {@code ROLE_AUDIT_ADMIN}</li>
 *   <li>POST — {@code ROLE_AUDIT_WRITE} or {@code ROLE_AUDIT_ADMIN}</li>
 *   <li>DELETE — {@code ROLE_AUDIT_ADMIN}</li>
 * </ul>
 * Fine-grained access is enforced at the method level via {@code @PreAuthorize}.
 * The filter-chain here provides a catch-all layer.
 * </p>
 * <p>
 * The {@link JwtDecoder} comes from the host application, or is built from
 * {@code audit.security.jwk-set-uri}. With neither, no authentication mechanism
 * is installed and every audit request is rejected.
 * </p>
 */
@Configuration
@EnableMethodSecurity
public class AuditSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditSecurityConfig.class);

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    @ConditionalOnMissingBean(name = "auditSecurityFilterChain")
    public SecurityFilterChain auditSecurityFilterChain(HttpSecurity http,
                                                        AuditProperties properties,
                                                        ObjectProvider<JwtDecoder> decoders,
                                                        ObjectProvider<JwtAuthenticationConverter> converters) throws Exception {
        String basePath = properties.getApi().getBasePath() + "/**";
        String askPath = properties.getApi().getBasePath() + "/ask";   // a POST that only reads

        http
            .securityMatcher(basePath)
            .csrf(AbstractHttpConfigurer::disable)
            // Bearer tokens only. With CSRF off, a session cookie from the host's own login must not authenticate here.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, basePath).permitAll()
                .requestMatchers(HttpMethod.GET, basePath)
                    .hasAnyRole("AUDIT_READ", "AUDIT_ADMIN")
                .requestMatchers(HttpMethod.POST, askPath)
                    .hasAnyRole("AUDIT_READ", "AUDIT_ADMIN")
                .requestMatchers(HttpMethod.POST, basePath)
                    .hasAnyRole("AUDIT_WRITE", "AUDIT_ADMIN")
                .requestMatchers(HttpMethod.DELETE, basePath)
                    .hasRole("AUDIT_ADMIN")
                .anyRequest().authenticated()
            );

        JwtDecoder decoder = decoders.getIfAvailable(() -> jwkSetDecoder(properties));
        if (decoder != null) {
            JwtAuthenticationConverter converter = converters.getIfAvailable(() -> rolesClaimConverter(properties));
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                    jwt.decoder(decoder).jwtAuthenticationConverter(converter)));
        } else {
            log.warn("No JwtDecoder bean and no audit.security.jwk-set-uri: audit endpoints reject every request");
        }

        return http.build();
    }

    /**
     * Spring Boot backs off its own default chain once any {@link SecurityFilterChain} exists,
     * which would leave every non-audit route unprotected. This restores that default.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "auditFallbackSecurityFilterChain")
    public SecurityFilterChain auditFallbackSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults())
                .build();
    }

    /**
     * Spring maps only the {@code scope} claim by default (authorities {@code SCOPE_*}), but the audit
     * endpoints check {@code ROLE_AUDIT_*}. Read the roles claim and prefix each value with {@code ROLE_}.
     */
    static JwtAuthenticationConverter rolesClaimConverter(AuditProperties properties) {
        String claim = properties.getSecurity().getRolesClaim();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(properties.getSecurity().getPrincipalClaim());
        converter.setJwtGrantedAuthoritiesConverter(jwt -> roles(jwt, claim).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList());
        return converter;
    }

    /**
     * The claim is looked up by its exact name first (Auth0 uses names like {@code https://app/roles}), then as a
     * dotted path into nested objects (Keycloak: {@code realm_access.roles}). A string value is split on spaces.
     */
    private static List<String> roles(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value == null && claim.contains(".")) {
            Object node = jwt.getClaims();
            for (String part : claim.split("\\.")) {
                node = node instanceof Map<?, ?> map ? map.get(part) : null;
            }
            value = node;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim().split("\\s+"));
        }
        return List.of();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static JwtDecoder jwkSetDecoder(AuditProperties properties) {
        String uri = properties.getSecurity().getJwkSetUri();
        if (uri == null || uri.isBlank()) return null;
        AuditProperties.SecurityProperties security = properties.getSecurity();
        if (isBlank(security.getIssuer())) {
            // Without an issuer check, a token signed by the same identity provider for another application is accepted.
            throw new IllegalStateException("audit.security.jwk-set-uri requires audit.security.issuer. "
                    + "Set the expected 'iss' claim, or define your own JwtDecoder bean.");
        }
        if (isBlank(security.getAudience())) {
            log.warn("audit.security.audience is not set: tokens issued for another application of the same "
                    + "issuer are accepted");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(uri).build();
        decoder.setJwtValidator(tokenValidator(properties.getSecurity()));
        return decoder;
    }

    /**
     * Signature and expiry alone accept any token the identity provider signed, including ones issued
     * for another application. The issuer and audience checks narrow that to tokens meant for this one.
     */
    static OAuth2TokenValidator<Jwt> tokenValidator(AuditProperties.SecurityProperties security) {
        String issuer = security.getIssuer();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(issuer == null || issuer.isBlank()
                ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(issuer));
        String audience = security.getAudience();
        if (audience != null && !audience.isBlank()) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(audience)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
