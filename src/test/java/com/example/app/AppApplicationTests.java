package com.example.app;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	AppUserRepository appUserRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	DataSource dataSource;

	@Test
	void contextLoads() {
	}

	@Test
	void actuatorHealthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void anonymousGetAppRedirectsToLogin() throws Exception {
		mvc.perform(get("/app"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/login*"));
	}

	@Test
	void seededUserCanAuthenticate() throws Exception {
		String email = "seeded@example.com";
		String password = "correctHorseBatteryStaple";
		appUserRepository.save(new AppUser(email, passwordEncoder.encode(password)));

		mvc.perform(formLogin("/login").user(email).password(password))
				.andExpect(authenticated());
	}

	@Test
	void persistentLoginsTableExists() throws Exception {
		try (Connection conn = dataSource.getConnection();
		     ResultSet rs = conn.getMetaData().getTables(null, null, "PERSISTENT_LOGINS", null)) {
			assertThat(rs.next()).as("persistent_logins table should exist").isTrue();
		}
	}

	@Test
	void getSignupPageIsPublic() throws Exception {
		mvc.perform(get("/signup"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("action=\"/signup\"")));
	}

	@Test
	void signupHappyPathCreatesUserAndAutoLogsIn() throws Exception {
		String email = "happy@example.com";
		mvc.perform(post("/signup")
						.param("email", email)
						.param("password", "verylongpassword12")
						.with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/app"));
		assertThat(appUserRepository.existsByEmail(email)).isTrue();
	}

	@Test
	void signupDuplicateEmailRendersFieldError() throws Exception {
		String email = "dup@example.com";
		appUserRepository.save(new AppUser(email, passwordEncoder.encode("verylongpassword12")));

		mvc.perform(post("/signup")
						.param("email", email)
						.param("password", "verylongpassword12")
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Email already in use")));
	}

	@Test
	void signupMixedCaseEmailNormalizesToLowercase() throws Exception {
		mvc.perform(post("/signup")
						.param("email", "Alice@Example.COM")
						.param("password", "verylongpassword12")
						.with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/app"));

		assertThat(appUserRepository.findByEmail("alice@example.com")).isPresent();
		assertThat(appUserRepository.findByEmail("Alice@Example.COM")).isEmpty();

		mvc.perform(post("/signup")
						.param("email", "ALICE@example.com")
						.param("password", "verylongpassword12")
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Email already in use")));
	}

	@Test
	void signupShortPasswordRendersFieldError() throws Exception {
		mvc.perform(post("/signup")
						.param("email", "short@example.com")
						.param("password", "tooshort")
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("at least 12")));
	}

	@Test
	void getLoginPageIsPublic() throws Exception {
		mvc.perform(get("/login"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("action=\"/login\"")));
	}

	@Test
	void loginHappyPath() throws Exception {
		String email = "loginhappy@example.com";
		String password = "verylongpassword12";
		appUserRepository.save(new AppUser(email, passwordEncoder.encode(password)));

		mvc.perform(formLogin("/login").user(email).password(password))
				.andExpect(authenticated())
				.andExpect(redirectedUrl("/app"));
	}

	@Test
	void loginMixedCaseEmailAuthenticates() throws Exception {
		String email = "alice2@example.com";
		String password = "verylongpassword12";
		appUserRepository.save(new AppUser(email, passwordEncoder.encode(password)));

		mvc.perform(formLogin("/login").user("ALICE2@Example.com").password(password))
				.andExpect(authenticated())
				.andExpect(redirectedUrl("/app"));
	}

	@Test
	void loginBadPasswordShowsGenericError() throws Exception {
		String email = "badpass@example.com";
		appUserRepository.save(new AppUser(email, passwordEncoder.encode("verylongpassword12")));

		mvc.perform(formLogin("/login").user(email).password("wrongpassword"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void getAppAuthenticatedShowsEmail() throws Exception {
		mvc.perform(get("/app").with(user("showmail@example.com")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("showmail@example.com")));
	}

	@Test
	void logoutInvalidatesSessionAndRedirects() throws Exception {
		mvc.perform(post("/logout").with(user("logout@example.com")).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?logout"));

		mvc.perform(get("/app"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/login*"));
	}

	@Test
	void rootRedirectsAnonymousToLogin() throws Exception {
		mvc.perform(get("/"))
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void rootRedirectsAuthenticatedToApp() throws Exception {
		mvc.perform(get("/").with(user("rootauth@example.com")))
				.andExpect(redirectedUrl("/app"));
	}

}
