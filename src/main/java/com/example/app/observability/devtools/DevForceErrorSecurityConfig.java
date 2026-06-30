package com.example.app.observability.devtools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dev-only security overlay for {@link DevForceErrorController}. Adds a
 * separate {@link SecurityFilterChain} that matches {@code /__dev/force-error/**}
 * with HTTP Basic auth enabled and CSRF disabled — so the Phase 3 smoke runbook
 * works as written ({@code curl -X POST -u <devuser>:<pass> …}). The default
 * filter chain in {@link com.example.app.config.SecurityConfig} continues to
 * handle every other URL with form login + CSRF on.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} guarantees this chain is matched before
 * the default one. Gated by {@code @Profile("dev")} — under any other profile
 * the bean is absent and {@code /__dev/force-error/**} falls through to the
 * default chain (where it 404s, since {@link DevForceErrorController} is also
 * dev-only).
 */
@Configuration
@Profile("dev")
public class DevForceErrorSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain devForceErrorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/__dev/force-error/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(httpBasic -> {})
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
