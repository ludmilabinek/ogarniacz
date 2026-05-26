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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

}
