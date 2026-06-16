package com.example.app;

import com.example.app.event.Event;
import com.example.app.event.EventRepository;
import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

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
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class AppApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	AppUserRepository appUserRepository;

	@Autowired
	EventRepository eventRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	DataSource dataSource;

	@Autowired
	Clock clock;

	@Test
	void contextLoads() {
	}

	@Test
	void clockBeanReportsEuropeWarsawZone() {
		// Regression: the Clock bean must use app.timezone (Europe/Warsaw), not
		// systemDefaultZone (UTC on Fly). Consumers compute "today" against this
		// clock and must see Warsaw midnight, not UTC midnight.
		assertThat(clock.getZone()).isEqualTo(ZoneId.of("Europe/Warsaw"));
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
		UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

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
		UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

		mvc.perform(formLogin("/login").user(email).password("wrongpassword"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void getAppAuthenticatedShowsEmail() throws Exception {
		String email = "showmail@example.com";
		UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

		mvc.perform(get("/app").with(user(email)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(email)));
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

	@Test
	void appShowsOwnEmailOnlyNotOtherUsersEmail() throws Exception {
		String alice = "alice-partition@example.com";
		String bob = "bob-partition@example.com";
		UserTestFixtures.saveUser(appUserRepository, passwordEncoder, alice);
		UserTestFixtures.saveUser(appUserRepository, passwordEncoder, bob);

		mvc.perform(get("/app").with(user(alice)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(alice)))
				.andExpect(content().string(not(containsString(bob))));
	}

	@Test
	void appShowsUpcomingEventsForCurrentUserOnly() throws Exception {
		String aliceEmail = "alice-event-partition@example.com";
		String bobEmail = "bob-event-partition@example.com";
		AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, aliceEmail);
		AppUser bob = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, bobEmail);

		eventRepository.save(new Event(alice, LocalDate.now().plusDays(3), null, "alice-only-event-title", null, null));
		eventRepository.save(new Event(bob, LocalDate.now().plusDays(4), null, "bob-only-event-title", null, null));

		mvc.perform(get("/app").with(user(aliceEmail)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("alice-only-event-title")))
				.andExpect(content().string(not(containsString("bob-only-event-title"))));
	}

	@Test
	void rememberMeCookieReAuthenticatesAfterSessionEnds() throws Exception {
		String email = "remember@example.com";
		String password = "correctHorseBatteryStaple";
		appUserRepository.save(new AppUser(email, passwordEncoder.encode(password)));

		MvcResult loginResult = mvc.perform(formLogin("/login").user(email).password(password))
				.andExpect(authenticated())
				.andReturn();

		Cookie rememberMeCookie = loginResult.getResponse().getCookie("remember-me");
		assertThat(rememberMeCookie).as("remember-me cookie should be issued (alwaysRemember=true)").isNotNull();
		assertThat(rememberMeCookie.getValue()).isNotBlank();

		try (Connection conn = dataSource.getConnection();
		     PreparedStatement ps = conn.prepareStatement(
				     "SELECT count(*) FROM persistent_logins WHERE username = ?")) {
			ps.setString(1, email);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt(1)).as("persistent_logins row should exist for the user").isEqualTo(1);
			}
		}

		mvc.perform(get("/app").cookie(rememberMeCookie))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(email)));
	}

}
