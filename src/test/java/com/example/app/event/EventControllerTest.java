package com.example.app.event;

import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class EventControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void anonymousGetEventsNewRedirectsToLogin() throws Exception {
        mvc.perform(get("/events/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login*"));
    }

    @Test
    void authenticatedGetEventsNewRenders() throws Exception {
        mvc.perform(get("/events/new").with(user("alice-getform@example.com")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/events\"")))
                .andExpect(content().string(containsString("type=\"date\"")));
    }

    @Test
    void postEventHappyPathRedirectsToApp() throws Exception {
        String email = "alice-happy@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        String uniqueTitle = "happy-path-event-" + System.nanoTime();
        LocalDate future = LocalDate.now().plusDays(5);

        mvc.perform(post("/events")
                        .with(user(email))
                        .with(csrf())
                        .param("eventDate", future.toString())
                        .param("title", uniqueTitle)
                        .param("requirements", "Yellow shirt")
                        .param("notes", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        // Do NOT assert count() — the @SpringBootTest context is shared across tests
        // and other methods in this class persist events too. Match by title. The
        // finder is already user-scoped, so a hit on alice's list proves ownership
        // without dereferencing the lazy @ManyToOne proxy outside a session.
        List<Event> upcoming = eventRepository.findUpcomingByUser(alice, LocalDate.now());
        assertThat(upcoming)
                .filteredOn(e -> uniqueTitle.equals(e.getTitle()))
                .hasSize(1)
                .first()
                .satisfies(e -> assertThat(e.getEventDate()).isEqualTo(future));
    }

    @Test
    void postEventBlankTitleRendersFieldError() throws Exception {
        mvc.perform(post("/events")
                        .with(user("alice-blank@example.com"))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().plusDays(1).toString())
                        .param("title", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("must not be blank")));
    }

    @Test
    void postEventMissingDateRendersFieldError() throws Exception {
        mvc.perform(post("/events")
                        .with(user("alice-nodate@example.com"))
                        .with(csrf())
                        .param("title", "valid title"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("field-error")));
    }

    @Test
    void postEventOversizeTitleRendersFieldError() throws Exception {
        String tooLong = "a".repeat(201);
        mvc.perform(post("/events")
                        .with(user("alice-long@example.com"))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().plusDays(1).toString())
                        .param("title", tooLong))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("size must be between")));
    }

    @Test
    void postEventPastDateIsAccepted() throws Exception {
        String email = "alice-past@example.com";
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        mvc.perform(post("/events")
                        .with(user(email))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().minusDays(7).toString())
                        .param("title", "past-date-accepted-event-" + System.nanoTime()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));
    }

    @Test
    void postEventWithoutCsrfIs403() throws Exception {
        mvc.perform(post("/events")
                        .with(user("alice-nocsrf@example.com"))
                        .param("eventDate", LocalDate.now().plusDays(1).toString())
                        .param("title", "no-csrf"))
                .andExpect(status().isForbidden());
    }

    @Test
    void appHidesPastEventsForCurrentUser() throws Exception {
        String email = "alice-pasthide@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        eventRepository.save(new Event(alice, LocalDate.now().minusDays(2), null, "past-event-title-pasthide", null, null));
        eventRepository.save(new Event(alice, LocalDate.now().plusDays(2), null, "future-event-title-pasthide", null, null));

        mvc.perform(get("/app").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("future-event-title-pasthide")))
                .andExpect(content().string(not(containsString("past-event-title-pasthide"))));
    }
}
