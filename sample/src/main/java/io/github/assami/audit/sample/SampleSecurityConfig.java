package io.github.assami.audit.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Lets load balancers and Docker probe the liveness/readiness endpoints without a token. */
@Configuration
public class SampleSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain healthProbeFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/health/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
