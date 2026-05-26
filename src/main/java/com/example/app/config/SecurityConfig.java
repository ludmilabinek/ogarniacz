package com.example.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;

@Configuration
public class SecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
    JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
    repo.setDataSource(dataSource);
    repo.setCreateTableOnStartup(false);
    return repo;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http,
                                         PersistentTokenRepository tokenRepository,
                                         UserDetailsService userDetailsService) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/signup", "/login", "/actuator/health",
                "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
            .anyRequest().authenticated()
        )
        .formLogin(Customizer.withDefaults())
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        .rememberMe(rm -> rm
            .tokenRepository(tokenRepository)
            .userDetailsService(userDetailsService)
            .tokenValiditySeconds(60 * 60 * 24 * 30)
            .alwaysRemember(true)
        );
    return http.build();
  }
}
