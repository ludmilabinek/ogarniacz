package com.example.app.event;

import com.example.app.event.ProposedEvent.ProposedEventStatus;
import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class EventReviewControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    ProposedEventRepository proposedEventRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ExtractionJobRegistry jobRegistry;

    @MockitoBean
    ExtractionService extractionService;

    @Test
    void getRendersFormWithProposals() throws Exception {
        Fixture f = fixture("review-get-form@example.com");
        ProposedEvent p1 = saveProposal(f.image, LocalDate.of(2026, 9, 1),
                "review-get-form-rozpoczecie", "yellow shirt required");
        ProposedEvent p2 = saveProposal(f.image, LocalDate.of(2026, 9, 5),
                "review-get-form-zebranie", null);

        mvc.perform(get("/events/from-image/{id}/review", f.image.getId()).with(user(f.email)))
                .andExpect(status().isOk())
                .andExpect(view().name("events/review"))
                .andExpect(content().string(containsString("review-get-form-rozpoczecie")))
                .andExpect(content().string(containsString("review-get-form-zebranie")))
                .andExpect(content().string(containsString("yellow shirt required")))
                .andExpect(content().string(containsString(p1.getId().toString())))
                .andExpect(content().string(containsString(p2.getId().toString())));
    }

    @Test
    void postWithAllAcceptPromotesAndRedirectsToAppWithFlash() throws Exception {
        Fixture f = fixture("review-post-accept@example.com");
        ProposedEvent p1 = saveProposal(f.image, LocalDate.of(2026, 9, 1),
                "review-post-accept-one", null);
        ProposedEvent p2 = saveProposal(f.image, LocalDate.of(2026, 9, 2),
                "review-post-accept-two", null);

        mvc.perform(post("/events/from-image/{id}/decisions", f.image.getId())
                        .with(user(f.email))
                        .with(csrf())
                        .param("decisions[0].proposedEventId", p1.getId().toString())
                        .param("decisions[0].action", "ACCEPT")
                        .param("decisions[0].eventDate", "2026-09-01")
                        .param("decisions[0].title", "review-post-accept-one-edited")
                        .param("decisions[1].proposedEventId", p2.getId().toString())
                        .param("decisions[1].action", "ACCEPT")
                        .param("decisions[1].eventDate", "2026-09-02")
                        .param("decisions[1].title", "review-post-accept-two-edited"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"))
                .andExpect(flash().attribute("successMessage", "Dodano 2 wydarzeń."));

        assertThat(eventRepository.findUpcomingByUser(f.user, LocalDate.of(2026, 1, 1)))
                .extracting(Event::getTitle)
                .contains("review-post-accept-one-edited", "review-post-accept-two-edited");

        List<ProposedEvent> reloaded = proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(f.image);
        assertThat(reloaded).allSatisfy(p ->
                assertThat(p.getStatus()).isEqualTo(ProposedEventStatus.ACCEPTED));
    }

    @Test
    void postWithInvalidTitleOnAcceptRowReRendersWithError() throws Exception {
        Fixture f = fixture("review-post-bad-title@example.com");
        ProposedEvent p1 = saveProposal(f.image, LocalDate.of(2026, 9, 1),
                "review-post-bad-title-original", null);

        mvc.perform(post("/events/from-image/{id}/decisions", f.image.getId())
                        .with(user(f.email))
                        .with(csrf())
                        .param("decisions[0].proposedEventId", p1.getId().toString())
                        .param("decisions[0].action", "ACCEPT")
                        .param("decisions[0].eventDate", "2026-09-01")
                        .param("decisions[0].title", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("events/review"))
                .andExpect(content().string(containsString("field-error")));

        // The original proposal must not have flipped — error path is a re-render.
        ProposedEvent reloaded = proposedEventRepository.findById(p1.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProposedEventStatus.PENDING);
        assertThat(eventRepository.findUpcomingByUser(f.user, LocalDate.of(2026, 1, 1)))
                .filteredOn(e -> "review-post-bad-title-original".equals(e.getTitle()))
                .isEmpty();
    }

    @Test
    void postWithInvalidDateOnRejectRowIsAccepted() throws Exception {
        Fixture f = fixture("review-post-reject-bad-date@example.com");
        ProposedEvent p1 = saveProposal(f.image, LocalDate.of(2026, 9, 1),
                "review-post-reject-bad-date-row", null);

        // action=REJECT with empty eventDate and empty title — validation must skip
        // this row entirely. This is the load-bearing test for the "@Valid can't
        // conditionally skip rows" justification.
        mvc.perform(post("/events/from-image/{id}/decisions", f.image.getId())
                        .with(user(f.email))
                        .with(csrf())
                        .param("decisions[0].proposedEventId", p1.getId().toString())
                        .param("decisions[0].action", "REJECT")
                        .param("decisions[0].eventDate", "")
                        .param("decisions[0].title", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));

        ProposedEvent reloaded = proposedEventRepository.findById(p1.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProposedEventStatus.REJECTED);
    }

    @Test
    void getAfterDecisionsRendersOnlyPendingRows() throws Exception {
        Fixture f = fixture("review-get-only-pending@example.com");
        ProposedEvent accepted = saveProposal(f.image, LocalDate.of(2026, 9, 1),
                "review-only-pending-accepted-row", null);
        ProposedEvent rejected = saveProposal(f.image, LocalDate.of(2026, 9, 2),
                "review-only-pending-rejected-row", null);
        ProposedEvent pending = saveProposal(f.image, LocalDate.of(2026, 9, 3),
                "review-only-pending-still-pending", null);
        accepted.setStatus(ProposedEventStatus.ACCEPTED);
        rejected.setStatus(ProposedEventStatus.REJECTED);
        proposedEventRepository.save(accepted);
        proposedEventRepository.save(rejected);

        MvcResult result = mvc.perform(get("/events/from-image/{id}/review", f.image.getId())
                        .with(user(f.email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("review-only-pending-still-pending")))
                .andExpect(content().string(not(containsString("review-only-pending-accepted-row"))))
                .andExpect(content().string(not(containsString("review-only-pending-rejected-row"))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Sanity: the still-pending row's id should appear once as a hidden input.
        assertThat(body).contains(pending.getId().toString());
    }

    @Test
    void getCrossUserReturns404() throws Exception {
        Fixture owner = fixture("review-cross-owner@example.com");
        String intruderEmail = "review-cross-intruder@example.com";
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, intruderEmail);

        mvc.perform(get("/events/from-image/{id}/review", owner.image.getId())
                        .with(user(intruderEmail)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnknownImageReturns404() throws Exception {
        String email = "review-get-unknown@example.com";
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        mvc.perform(get("/events/from-image/{id}/review", UUID.randomUUID()).with(user(email)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRendersErrorTemplateWhenLastErrorKindSet() throws Exception {
        String email = "review-error-branch@example.com";
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = new SourceImage(user, new byte[]{1}, "image/jpeg");
        image.setLastErrorKind("TIMEOUT");
        image.setCorrelationId("abc12345");
        sourceImageRepository.save(image);

        mvc.perform(get("/events/from-image/{id}/review", image.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("events/review-error"))
                .andExpect(content().string(containsString("Wyciąganie wydarzeń zajęło za długo")))
                .andExpect(content().string(containsString("abc12345")));
    }

    @Test
    void getRendersRunningTemplateWhenExtractionInFlight() throws Exception {
        String email = "review-running-branch@example.com";
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{1}, "image/jpeg"));
        // No proposals, no error, no resolvedAt, plus a RUNNING job in the registry.
        jobRegistry.register(image.getId());

        mvc.perform(get("/events/from-image/{id}/review", image.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("events/review-running"))
                .andExpect(content().string(containsString("Wyciąganie wydarzeń trwa")));
    }

    @Test
    void getRendersEmptyTemplateWhenNoProposalsAndNoError() throws Exception {
        String email = "review-empty-branch@example.com";
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{1}, "image/jpeg"));
        // No proposals, no error, no in-flight job → empty branch.

        mvc.perform(get("/events/from-image/{id}/review", image.getId()).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(view().name("events/review-empty"))
                .andExpect(content().string(containsString("Nie znaleźliśmy wydarzeń")));
    }

    @Test
    void postRetryClearsErrorAndKicksExtraction() throws Exception {
        String email = "review-retry-happy@example.com";
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = new SourceImage(user, new byte[]{1}, "image/jpeg");
        image.setLastErrorKind("TIMEOUT");
        image.setCorrelationId("abc12345");
        sourceImageRepository.save(image);

        MvcResult result = mvc.perform(post("/events/from-image/{id}/retry", image.getId())
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.statusUrl").isString())
                .andExpect(jsonPath("$.reviewUrl").value(
                        "/events/from-image/" + image.getId() + "/review"))
                .andReturn();

        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isNull();
        assertThat(reloaded.getCorrelationId()).isNull();

        ArgumentCaptor<UUID> jobIdCap = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> imageIdCap = ArgumentCaptor.forClass(UUID.class);
        verify(extractionService).runExtraction(jobIdCap.capture(), imageIdCap.capture());
        assertThat(imageIdCap.getValue()).isEqualTo(image.getId());
        assertThat(jobRegistry.get(jobIdCap.getValue())).isPresent();
        // Sanity: the registered jobId is the one returned in the JSON envelope.
        assertThat(result.getResponse().getContentAsString())
                .contains(jobIdCap.getValue().toString());
    }

    @Test
    void postRetryCrossUserReturns404() throws Exception {
        AppUser owner = UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "review-retry-owner@example.com");
        String intruderEmail = "review-retry-intruder@example.com";
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder, intruderEmail);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(owner, new byte[]{1}, "image/jpeg"));

        mvc.perform(post("/events/from-image/{id}/retry", image.getId())
                        .with(user(intruderEmail))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReviewAfterPurgeReturns404() throws Exception {
        // S-06 post-purge contract: a purged image collapses to plain 404, indistinguishable
        // from wrong-user / unknown-id. The controller's 404 origin (per JavaDoc) is the
        // findByIdAndUser miss — so the most truthful seed is a direct repository delete,
        // not invoking SourceImagePurgeService. Predicate coverage lives in
        // SourceImagePurgeServiceTest; here we only need the row gone.
        String email = "alice-review-postpurge@example.com";
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{1, 2}, "image/jpeg"));
        UUID imageId = image.getId();

        sourceImageRepository.deleteById(imageId);
        sourceImageRepository.flush();
        assertThat(sourceImageRepository.findById(imageId)).isEmpty();

        mvc.perform(get("/events/from-image/{id}/review", imageId).with(user(email)))
                .andExpect(status().isNotFound());
    }

    private Fixture fixture(String email) {
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{1, 2}, "image/jpeg"));
        return new Fixture(email, user, image);
    }

    private ProposedEvent saveProposal(SourceImage image, LocalDate date, String title, String requirements) {
        return proposedEventRepository.save(new ProposedEvent(
                image, date, null, title, requirements, null));
    }

    private record Fixture(String email, AppUser user, SourceImage image) { }
}
