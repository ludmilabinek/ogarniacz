package com.example.app.user;

import com.example.app.testsupport.UserTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class SettingsControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    IcalSubscriptionService subscriptionService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void anonymousSettingsGetRedirectsToLogin() throws Exception {
        mvc.perform(get("/settings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login*"));
    }

    @Test
    void authenticatedGetRendersUrlWithUserToken() throws Exception {
        AppUser alice = saveUser("settings-render@example.com");
        String token = subscriptionService.getOrCreateToken(alice);

        mvc.perform(get("/settings").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(token)))
                .andExpect(content().string(containsString("/calendar/")))
                .andExpect(content().string(containsString(".ics")));
    }

    @Test
    void firstVisitMintsTokenAndPersists() throws Exception {
        AppUser alice = saveUser("settings-first-visit@example.com");
        assertThat(alice.getIcalToken()).isNull();

        MvcResult result = mvc.perform(get("/settings").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        AppUser refreshed = appUserRepository.findById(alice.getId()).orElseThrow();
        assertThat(refreshed.getIcalToken())
                .as("first /settings visit should mint and persist a token")
                .isNotNull()
                .matches("^[A-Za-z0-9_-]{32}$");
        assertThat(body)
                .as("rendered page should contain the freshly-minted token")
                .contains(refreshed.getIcalToken());
    }

    @Test
    void subsequentVisitsReuseSameToken() throws Exception {
        AppUser alice = saveUser("settings-idempotent@example.com");

        mvc.perform(get("/settings").with(user(alice.getEmail()))).andExpect(status().isOk());
        String firstToken = appUserRepository.findById(alice.getId()).orElseThrow().getIcalToken();

        mvc.perform(get("/settings").with(user(alice.getEmail()))).andExpect(status().isOk());
        String secondToken = appUserRepository.findById(alice.getId()).orElseThrow().getIcalToken();

        assertThat(secondToken)
                .as("second /settings visit must NOT mutate the token")
                .isEqualTo(firstToken);
    }

    @Test
    void settingsShowsOwnTokenOnlyNotOtherUsersToken() throws Exception {
        AppUser alice = saveUser("settings-alice-partition@example.com");
        AppUser bob = saveUser("settings-bob-partition@example.com");
        String aliceToken = subscriptionService.getOrCreateToken(alice);
        String bobToken = subscriptionService.getOrCreateToken(bob);
        assertThat(aliceToken).isNotEqualTo(bobToken);

        mvc.perform(get("/settings").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(aliceToken)))
                .andExpect(content().string(not(containsString(bobToken))));
    }

    @Test
    void settingsBodyMentionsGoogleLimitationsNote() throws Exception {
        AppUser alice = saveUser("settings-google-note@example.com");

        mvc.perform(get("/settings").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Google")))
                .andExpect(content().string(containsString("subscribed")));
    }

    private AppUser saveUser(String email) {
        return UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
    }
}
