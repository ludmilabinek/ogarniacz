package com.example.app.event;

import com.example.app.testsupport.UserTestFixtures;
import com.example.app.testsupport.UserTestFixtures.SeededUser;
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
    SourceImageRepository sourceImageRepository;

    @Autowired
    ProposedEventRepository proposedEventRepository;

    @Autowired
    IcalSubscriptionService subscriptionService;

    @Autowired
    IcalTokenGenerator tokenGenerator;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void anonymousFeedGetReturns200NotRedirect() throws Exception {
        SeededUser seed = seedUserWithToken("anonymous-feed@example.com");

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(status().isOk())
                .andExpect(status().is(not(302)));
    }

    @Test
    void feedReturnsCorrectMimeAndCacheControl() throws Exception {
        SeededUser seed = seedUserWithToken("mime-and-cache@example.com");

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        allOf(containsString("text/calendar"), containsString("charset=UTF-8"))))
                .andExpect(header().string("Cache-Control", "private, no-cache, max-age=0"))
                .andExpect(header().string("Vary", "Accept"));
    }

    @Test
    void feedBodyContainsExpectedEventUids() throws Exception {
        SeededUser seed = seedUserWithToken("body-uids@example.com");
        Event e1 = eventRepository.save(new Event(
                seed.user(), LocalDate.now().plusDays(3), null, "body-uids-event-1", null, null));
        Event e2 = eventRepository.save(new Event(
                seed.user(), LocalDate.now().plusDays(5), null, "body-uids-event-2", null, null));

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
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
        SeededUser alice = seedUserWithToken("cross-isolation-alice@example.com");
        SeededUser bob = seedUserWithToken("cross-isolation-bob@example.com");
        Event aliceEvent = eventRepository.save(new Event(
                alice.user(), LocalDate.now().plusDays(3), null, "alice-cross-isolation-event", null, null));
        Event bobEvent = eventRepository.save(new Event(
                bob.user(), LocalDate.now().plusDays(4), null, "bob-cross-isolation-event", null, null));

        String aliceUid = aliceEvent.getId() + "@ogarniacz.fly.dev";
        String bobUid = bobEvent.getId() + "@ogarniacz.fly.dev";

        mvc.perform(get("/calendar/{token}.ics", alice.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(aliceUid)))
                .andExpect(content().string(not(containsString(bobUid))));

        mvc.perform(get("/calendar/{token}.ics", bob.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(bobUid)))
                .andExpect(content().string(not(containsString(aliceUid))));
    }

    @Test
    void deletedEventDisappearsFromNextPoll() throws Exception {
        SeededUser seed = seedUserWithToken("deletion-prop@example.com");
        Event e1 = eventRepository.save(new Event(
                seed.user(), LocalDate.now().plusDays(3), null, "deletion-prop-keep", null, null));
        Event e2 = eventRepository.save(new Event(
                seed.user(), LocalDate.now().plusDays(5), null, "deletion-prop-delete", null, null));

        String keepUid = e1.getId() + "@ogarniacz.fly.dev";
        String deleteUid = e2.getId() + "@ogarniacz.fly.dev";

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(content().string(containsString(keepUid)))
                .andExpect(content().string(containsString(deleteUid)));

        eventRepository.delete(e2);

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(content().string(containsString(keepUid)))
                .andExpect(content().string(not(containsString(deleteUid))));
    }

    @Test
    void pastEventsAreExcludedFromFeed() throws Exception {
        SeededUser seed = seedUserWithToken("past-excluded@example.com");
        Event past = eventRepository.save(new Event(
                seed.user(), LocalDate.now().minusDays(3), null, "past-excluded-yesterday", null, null));
        Event future = eventRepository.save(new Event(
                seed.user(), LocalDate.now().plusDays(3), null, "past-excluded-future", null, null));

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(future.getId() + "@ogarniacz.fly.dev")))
                .andExpect(content().string(not(containsString(past.getId() + "@ogarniacz.fly.dev"))));
    }

    @Test
    void emptyAcceptedEventsReturnsEnvelopeOnly() throws Exception {
        SeededUser seed = seedUserWithToken("empty-envelope@example.com");

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BEGIN:VCALENDAR")))
                .andExpect(content().string(containsString("END:VCALENDAR")))
                .andExpect(content().string(not(containsString("BEGIN:VEVENT"))));
    }

    @Test
    void icsFeedExcludesPendingProposedEvents() throws Exception {
        SeededUser seed = seedUserWithToken("pending-proposals@example.com");

        SourceImage image = sourceImageRepository.save(new SourceImage(seed.user(), new byte[]{4, 2}, "image/jpeg"));
        proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.now().plusDays(2), null, "pending-proposal-one", null, null));
        proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.now().plusDays(3), null, "pending-proposal-two", null, null));

        mvc.perform(get("/calendar/{token}.ics", seed.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/calendar")))
                .andExpect(content().string(containsString("BEGIN:VCALENDAR")))
                .andExpect(content().string(containsString("END:VCALENDAR")))
                .andExpect(content().string(not(containsString("BEGIN:VEVENT"))))
                .andExpect(content().string(not(containsString("pending-proposal"))));
    }

    @Test
    void tokenWithWrongFileExtensionRedirectsToLogin() throws Exception {
        SeededUser seed = seedUserWithToken("wrong-extension@example.com");

        // /calendar/<token>.txt falls outside the .ics carve-out; Spring Security's
        // form-login filter then redirects to /login. Mirrors
        // EventControllerTest.anonymousGetEventsNewRedirectsToLogin.
        mvc.perform(get("/calendar/{token}.txt", seed.token()))
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

    private SeededUser seedUserWithToken(String email) {
        return UserTestFixtures.seedUserWithToken(
                appUserRepository, passwordEncoder, subscriptionService, email);
    }
}
