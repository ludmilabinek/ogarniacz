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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
    void anonymousGetEventEditRedirectsToLogin() throws Exception {
        mvc.perform(get("/events/" + UUID.randomUUID() + "/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login*"));
    }

    @Test
    void authenticatedGetEventEditRendersPrefilledForm() throws Exception {
        String email = "alice-edit-render@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        String uniqueTitle = "edit-render-event-" + System.nanoTime();
        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(3), null, uniqueTitle, "Yellow shirt", null));

        mvc.perform(get("/events/" + event.getId() + "/edit").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(uniqueTitle)))
                .andExpect(content().string(containsString("action=\"/events/" + event.getId() + "\"")))
                .andExpect(content().string(containsString("min=")));
    }

    @Test
    void editFormCarriesPastDateSoftWarnOnsubmit() throws Exception {
        String email = "alice-edit-softwarn@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(2), null, "softwarn-event-" + System.nanoTime(), null, null));

        mvc.perform(get("/events/" + event.getId() + "/edit").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("onsubmit=\"return this.eventDate.value &gt;=")))
                .andExpect(content().string(containsString("confirm('Data jest w przeszłości — kontynuować?')")));
    }

    @Test
    void getEventEditForForeignUserReturns404() throws Exception {
        String bobEmail = "bob-edit-foreign@example.com";
        String aliceEmail = "alice-edit-foreign@example.com";
        AppUser bob = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, bobEmail);
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, aliceEmail);

        Event event = eventRepository.save(
                new Event(bob, LocalDate.now().plusDays(2), null, "bob-only-edit-" + System.nanoTime(), null, null));

        mvc.perform(get("/events/" + event.getId() + "/edit").with(user(aliceEmail)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getEventEditForPastEventReturns404() throws Exception {
        String email = "alice-edit-past@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().minusDays(1), null, "past-edit-" + System.nanoTime(), null, null));

        mvc.perform(get("/events/" + event.getId() + "/edit").with(user(email)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getEventEditForUnknownIdReturns404() throws Exception {
        String email = "alice-edit-unknown@example.com";
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        mvc.perform(get("/events/" + UUID.randomUUID() + "/edit").with(user(email)))
                .andExpect(status().isNotFound());
    }

    @Test
    void postEventUpdateHappyPathRedirectsToAppWithFlash() throws Exception {
        String email = "alice-edit-happy@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        LocalDate future = LocalDate.now().plusDays(4);
        Event event = eventRepository.save(
                new Event(alice, future, null, "original-title-" + System.nanoTime(), null, null));

        String newTitle = "updated-title-" + System.nanoTime();

        mvc.perform(post("/events/" + event.getId())
                        .with(user(email))
                        .with(csrf())
                        .param("eventDate", future.toString())
                        .param("title", newTitle)
                        .param("requirements", "")
                        .param("notes", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Zapisano zmiany w wydarzeniu „" + newTitle + "”."));

        Event reloaded = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo(newTitle);
        assertThat(reloaded.getEventDate()).isEqualTo(future);
    }

    @Test
    void postEventUpdateBlankTitleRendersFieldError() throws Exception {
        String email = "alice-edit-blank@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(2), null, "blank-edit-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId())
                        .with(user(email))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().plusDays(2).toString())
                        .param("title", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("field-error")))
                .andExpect(content().string(containsString("action=\"/events/" + event.getId() + "\"")));
    }

    @Test
    void postEventUpdateForForeignUserReturns404() throws Exception {
        String bobEmail = "bob-edit-update-foreign@example.com";
        String aliceEmail = "alice-edit-update-foreign@example.com";
        AppUser bob = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, bobEmail);
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, aliceEmail);

        Event event = eventRepository.save(
                new Event(bob, LocalDate.now().plusDays(2), null, "bob-update-foreign-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId())
                        .with(user(aliceEmail))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().plusDays(3).toString())
                        .param("title", "alice-hijack"))
                .andExpect(status().isNotFound());
    }

    @Test
    void postEventUpdateForPastEventReturns404() throws Exception {
        String email = "alice-edit-update-past@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().minusDays(2), null, "past-update-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId())
                        .with(user(email))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().plusDays(1).toString())
                        .param("title", "trying-to-resurrect"))
                .andExpect(status().isNotFound());
    }

    @Test
    void postEventUpdateWithoutCsrfIs403() throws Exception {
        String email = "alice-edit-update-csrf@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(2), null, "csrf-update-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId())
                        .with(user(email))
                        .param("eventDate", LocalDate.now().plusDays(3).toString())
                        .param("title", "no-csrf-update"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postEventUpdateMovingDateToPastReturnsSuccessAndRowVanishesFromApp() throws Exception {
        // Pinpoint contract test for Q6: EventForm stays symmetric across create + edit
        // (no @FutureOrPresent on edit). Moving a date to the past succeeds at the server,
        // but the row vanishes from /app via findUpcomingByUser and from re-edit via the
        // URL-guess guard. Adding @FutureOrPresent to EventForm later turns this red,
        // forcing the future reviewer to read the validation-symmetry lesson before changing
        // the rule. The soft-warn confirm() on the form is the in-slice mitigation.
        String email = "alice-edit-to-past@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        String uniqueTitle = "edit-to-past-" + System.nanoTime();
        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(1), null, uniqueTitle, null, null));

        mvc.perform(post("/events/" + event.getId())
                        .with(user(email))
                        .with(csrf())
                        .param("eventDate", LocalDate.now().minusDays(1).toString())
                        .param("title", uniqueTitle))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Zapisano zmiany w wydarzeniu „" + uniqueTitle + "”."));

        mvc.perform(get("/app").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(uniqueTitle))));
    }

    @Test
    void postEventDeleteHappyPathRedirectsToAppWithFlash() throws Exception {
        String email = "alice-delete-happy@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        String uniqueTitle = "delete-happy-" + System.nanoTime();
        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(3), null, uniqueTitle, null, null));

        mvc.perform(post("/events/" + event.getId() + "/delete")
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Usunięto wydarzenie „" + uniqueTitle + "”."));

        assertThat(eventRepository.findById(event.getId())).isEmpty();
    }

    @Test
    void postEventDeleteForForeignUserReturns404() throws Exception {
        String bobEmail = "bob-delete-foreign@example.com";
        String aliceEmail = "alice-delete-foreign@example.com";
        AppUser bob = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, bobEmail);
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, aliceEmail);

        Event event = eventRepository.save(
                new Event(bob, LocalDate.now().plusDays(2), null, "bob-only-delete-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId() + "/delete")
                        .with(user(aliceEmail))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(eventRepository.findById(event.getId())).isPresent();
    }

    @Test
    void postEventDeleteForPastEventReturns404() throws Exception {
        String email = "alice-delete-past@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().minusDays(1), null, "past-delete-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId() + "/delete")
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(eventRepository.findById(event.getId())).isPresent();
    }

    @Test
    void postEventDeleteWithoutCsrfIs403() throws Exception {
        String email = "alice-delete-csrf@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event event = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(2), null, "csrf-delete-" + System.nanoTime(), null, null));

        mvc.perform(post("/events/" + event.getId() + "/delete")
                        .with(user(email)))
                .andExpect(status().isForbidden());

        assertThat(eventRepository.findById(event.getId())).isPresent();
    }

    @Test
    void appShowsEditAndDeleteAffordancesPerRow() throws Exception {
        String email = "alice-affordances@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        Event e1 = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(2), null, "affordance-row-one-" + System.nanoTime(), null, null));
        Event e2 = eventRepository.save(
                new Event(alice, LocalDate.now().plusDays(3), null, "affordance-row-two-" + System.nanoTime(), null, null));

        mvc.perform(get("/app").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edytuj")))
                .andExpect(content().string(containsString("Usuń")))
                .andExpect(content().string(containsString("action=\"/events/" + e1.getId() + "/delete\"")))
                .andExpect(content().string(containsString("action=\"/events/" + e2.getId() + "/delete\"")))
                .andExpect(content().string(containsString("href=\"/events/" + e1.getId() + "/edit\"")))
                .andExpect(content().string(containsString("href=\"/events/" + e2.getId() + "/edit\"")));
    }

    @Test
    void createFormCarriesPastDateSoftWarnOnsubmit() throws Exception {
        mvc.perform(get("/events/new").with(user("alice-create-softwarn@example.com")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("onsubmit=\"return this.eventDate.value &gt;=")))
                .andExpect(content().string(containsString("confirm('Data jest w przeszłości — kontynuować?')")));
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
