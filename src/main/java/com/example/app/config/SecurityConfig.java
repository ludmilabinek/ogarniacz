package com.example.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

  private static final int THIRTY_DAYS_SECONDS = 60 * 60 * 24 * 30;

  private final String rememberMeKey;

  public SecurityConfig(@Value("${REMEMBER_ME_KEY}") String rememberMeKey) {
    this.rememberMeKey = rememberMeKey;
  }

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
                                                UserDetailsService userDetailsService,
                                                @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookie) {
    PersistentTokenBasedRememberMeServices svc = new PersistentTokenBasedRememberMeServices(
        rememberMeKey, userDetailsService, tokenRepository);
    svc.setTokenValiditySeconds(THIRTY_DAYS_SECONDS);
    svc.setAlwaysRemember(true);
    svc.setUseSecureCookie(secureCookie);
    return svc;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http,
                                         RememberMeServices rememberMeServices,
                                         SecurityContextRepository securityContextRepository) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            // Anonymous iCalendar feed — token entropy is the only access control.
            // MUST precede anyRequest().authenticated() (first-match-wins; silently
            // shadowed if ordered after — CalendarControllerTest pins this).
            .requestMatchers(HttpMethod.GET, "/calendar/*.ics").permitAll()
            .requestMatchers("/", "/signup", "/login", "/actuator/health",
                "/css/**", "/js/**", "/img/**", "/favicon.ico", "/error").permitAll()
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
            .key(rememberMeKey)
        )
        .securityContext(sc -> sc.securityContextRepository(securityContextRepository));
    return http.build();
  }
}
