package com.example.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import javax.sql.DataSource;

@Configuration
public class SecurityConfig {

  private static final String REMEMBER_ME_KEY = "ogarniacz-remember-me-key";
  private static final int THIRTY_DAYS_SECONDS = 60 * 60 * 24 * 30;

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
  public SecurityContextRepository securityContextRepository() {
    return new DelegatingSecurityContextRepository(
        new RequestAttributeSecurityContextRepository(),
        new HttpSessionSecurityContextRepository()
    );
  }

  @Bean
  public RememberMeServices rememberMeServices(PersistentTokenRepository tokenRepository,
                                                UserDetailsService userDetailsService) {
    PersistentTokenBasedRememberMeServices svc = new PersistentTokenBasedRememberMeServices(
        REMEMBER_ME_KEY, userDetailsService, tokenRepository);
    svc.setTokenValiditySeconds(THIRTY_DAYS_SECONDS);
    svc.setAlwaysRemember(true);
    return svc;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http,
                                         RememberMeServices rememberMeServices,
                                         SecurityContextRepository securityContextRepository) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/signup", "/login", "/actuator/health",
                "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
            .anyRequest().authenticated()
        )
        .formLogin(login -> login
            .loginPage("/login")
            .loginProcessingUrl("/login")
            .defaultSuccessUrl("/app", false)
            .failureUrl("/login?error")
            .permitAll()
        )
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        .rememberMe(rm -> rm
            .rememberMeServices(rememberMeServices)
            .key(REMEMBER_ME_KEY)
        )
        .securityContext(sc -> sc.securityContextRepository(securityContextRepository));
    return http.build();
  }
}
