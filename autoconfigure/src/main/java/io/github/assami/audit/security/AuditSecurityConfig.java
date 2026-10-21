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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

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
                                                        ObjectProvider<JwtDecoder> decoders) throws Exception {
        String basePath = properties.getApi().getBasePath() + "/**";

        http
            .securityMatcher(basePath)
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, basePath).permitAll()
                .requestMatchers(HttpMethod.GET, basePath)
                    .hasAnyRole("AUDIT_READ", "AUDIT_ADMIN")
                .requestMatchers(HttpMethod.POST, basePath)
                    .hasAnyRole("AUDIT_WRITE", "AUDIT_ADMIN")
                .requestMatchers(HttpMethod.DELETE, basePath)
                    .hasRole("AUDIT_ADMIN")
                .anyRequest().authenticated()
            );

        JwtDecoder decoder = decoders.getIfAvailable(() -> jwkSetDecoder(properties));
        if (decoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(decoder)));
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

    private static JwtDecoder jwkSetDecoder(AuditProperties properties) {
        String uri = properties.getSecurity().getJwkSetUri();
        return (uri == null || uri.isBlank()) ? null : NimbusJwtDecoder.withJwkSetUri(uri).build();
    }
}
