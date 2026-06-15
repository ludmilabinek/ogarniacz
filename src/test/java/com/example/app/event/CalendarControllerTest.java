package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import com.example.app.user.IcalSubscriptionService;
import com.example.app.user.IcalTokenGenerator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class CalendarControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    IcalSubscriptionService subscriptionService;

    @Autowired
    IcalTokenGenerator tokenGenerator;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void anonymousFeedGetReturns200NotRedirect() throws Exception {
        Seed seed = seedUserWithToken("anonymous-feed@example.com");

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(status().isOk())
                .andExpect(status().is(not(302)));
    }

    @Test
    void feedReturnsCorrectMimeAndCacheControl() throws Exception {
        Seed seed = seedUserWithToken("mime-and-cache@example.com");

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        allOf(containsString("text/calendar"), containsString("charset=UTF-8"))))
                .andExpect(header().string("Cache-Control", "private, no-cache, max-age=0"));
    }

    @Test
    void feedBodyContainsExpectedEventUids() throws Exception {
        Seed seed = seedUserWithToken("body-uids@example.com");
        Event e1 = eventRepository.save(new Event(
                seed.user, LocalDate.now().plusDays(3), null, "body-uids-event-1", null, null));
        Event e2 = eventRepository.save(new Event(
                seed.user, LocalDate.now().plusDays(5), null, "body-uids-event-2", null, null));

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BEGIN:VCALENDAR")))
                .andExpect(content().string(containsString("END:VCALENDAR")))
                .andExpect(content().string(containsString("PRODID:-//Ogarniacz//")))
                .andExpect(content().string(containsString(e1.getId() + "@ogarniacz.fly.dev")))
                .andExpect(content().string(containsString(e2.getId() + "@ogarniacz.fly.dev")));
    }

    @Test
    void unknownTokenReturns404NotUnauthorized() throws Exception {
        String randomToken = tokenGenerator.next();

        mvc.perform(get("/calendar/{token}.ics", randomToken))
                .andExpect(status().isNotFound())
                .andExpect(status().is(not(401)));
    }

    @Test
    void crossUserIsolationByToken() throws Exception {
        Seed alice = seedUserWithToken("cross-isolation-alice@example.com");
        Seed bob = seedUserWithToken("cross-isolation-bob@example.com");
        Event aliceEvent = eventRepository.save(new Event(
                alice.user, LocalDate.now().plusDays(3), null, "alice-cross-isolation-event", null, null));
        Event bobEvent = eventRepository.save(new Event(
                bob.user, LocalDate.now().plusDays(4), null, "bob-cross-isolation-event", null, null));

        String aliceUid = aliceEvent.getId() + "@ogarniacz.fly.dev";
        String bobUid = bobEvent.getId() + "@ogarniacz.fly.dev";

        mvc.perform(get("/calendar/{token}.ics", alice.token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(aliceUid)))
                .andExpect(content().string(not(containsString(bobUid))));

        mvc.perform(get("/calendar/{token}.ics", bob.token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(bobUid)))
                .andExpect(content().string(not(containsString(aliceUid))));
    }

    @Test
    void deletedEventDisappearsFromNextPoll() throws Exception {
        Seed seed = seedUserWithToken("deletion-prop@example.com");
        Event e1 = eventRepository.save(new Event(
                seed.user, LocalDate.now().plusDays(3), null, "deletion-prop-keep", null, null));
        Event e2 = eventRepository.save(new Event(
                seed.user, LocalDate.now().plusDays(5), null, "deletion-prop-delete", null, null));

        String keepUid = e1.getId() + "@ogarniacz.fly.dev";
        String deleteUid = e2.getId() + "@ogarniacz.fly.dev";

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(content().string(containsString(keepUid)))
                .andExpect(content().string(containsString(deleteUid)));

        eventRepository.delete(e2);

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(content().string(containsString(keepUid)))
                .andExpect(content().string(not(containsString(deleteUid))));
    }

    @Test
    void pastEventsAreExcludedFromFeed() throws Exception {
        Seed seed = seedUserWithToken("past-excluded@example.com");
        Event past = eventRepository.save(new Event(
                seed.user, LocalDate.now().minusDays(3), null, "past-excluded-yesterday", null, null));
        Event future = eventRepository.save(new Event(
                seed.user, LocalDate.now().plusDays(3), null, "past-excluded-future", null, null));

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(future.getId() + "@ogarniacz.fly.dev")))
                .andExpect(content().string(not(containsString(past.getId() + "@ogarniacz.fly.dev"))));
    }

    @Test
    void emptyAcceptedEventsReturnsEnvelopeOnly() throws Exception {
        Seed seed = seedUserWithToken("empty-envelope@example.com");

        mvc.perform(get("/calendar/{token}.ics", seed.token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BEGIN:VCALENDAR")))
                .andExpect(content().string(containsString("END:VCALENDAR")))
                .andExpect(content().string(not(containsString("BEGIN:VEVENT"))));
    }

    @Test
    void tokenWithWrongFileExtensionRedirectsToLogin() throws Exception {
        Seed seed = seedUserWithToken("wrong-extension@example.com");

        // /calendar/<token>.txt falls outside the .ics carve-out; Spring Security's
        // form-login filter then redirects to /login. Mirrors
        // EventControllerTest.anonymousGetEventsNewRedirectsToLogin.
        mvc.perform(get("/calendar/{token}.txt", seed.token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login*"));
    }

    @Test
    void tokenInPathOnlyAcceptsTokenCharsetRedirectsToLogin() throws Exception {
        // `/calendar/*.ics` is a single-segment matcher; /calendar/foo/bar.ics
        // does not match and is rejected by the security chain.
        mvc.perform(get("/calendar/foo/bar.ics"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login*"));
    }

    // --- helpers ----------------------------------------------------------

    private Seed seedUserWithToken(String email) {
        AppUser user = appUserRepository.save(new AppUser(email, passwordEncoder.encode("verylongpassword12")));
        String token = subscriptionService.getOrCreateToken(user);
        // Re-read so the in-memory entity reflects the persisted icalToken.
        AppUser refreshed = appUserRepository.findById(user.getId()).orElseThrow();
        return new Seed(refreshed, token);
    }

    private record Seed(AppUser user, String token) {
    }
}
