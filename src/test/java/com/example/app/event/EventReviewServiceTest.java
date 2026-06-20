package com.example.app.event;

import com.example.app.event.EventReviewForm.Action;
import com.example.app.event.EventReviewForm.ProposedEventDecision;
import com.example.app.event.ProposedEvent.ProposedEventStatus;
import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class EventReviewServiceTest {

    @Autowired
    EventReviewService eventReviewService;

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    ProposedEventRepository proposedEventRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void appliesMixedAcceptRejectAndPromotesAcceptedOnly() {
        AppUser user = saveUser("review-mixed@example.com");
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{1, 2, 3}, "image/jpeg"));
        ProposedEvent p1 = proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.of(2026, 9, 1), LocalTime.of(9, 0),
                "review-mixed-rozpoczecie", "strój galowy", "sala 12"));
        ProposedEvent p2 = proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.of(2026, 9, 5), null,
                "review-mixed-zebranie", null, null));
        ProposedEvent p3 = proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.of(2026, 9, 10), null,
                "review-mixed-wycieczka", null, null));

        EventReviewForm form = new EventReviewForm();
        form.setDecisions(List.of(
                accept(p1), accept(p2), reject(p3)));

        int accepted = eventReviewService.applyDecisions(image.getId(), user, form);

        assertThat(accepted).isEqualTo(2);
        assertThat(eventRepository.findUpcomingByUser(user, LocalDate.of(2026, 1, 1)))
                .filteredOn(e -> e.getTitle().startsWith("review-mixed-"))
                .extracting(Event::getTitle)
                .containsExactlyInAnyOrder("review-mixed-rozpoczecie", "review-mixed-zebranie");

        List<ProposedEvent> reloaded = proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image);
        assertThat(reloaded).extracting(ProposedEvent::getStatus)
                .containsExactly(
                        ProposedEventStatus.ACCEPTED,
                        ProposedEventStatus.ACCEPTED,
                        ProposedEventStatus.REJECTED);
        assertThat(reloaded).allSatisfy(p -> assertThat(p.getDecidedAt()).isNotNull());

        SourceImage refreshed = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(refreshed.getResolvedAt()).isNotNull();
    }

    @Test
    void idempotentSecondSubmitDoesNotDuplicateEventsAndPreservesResolvedAt() {
        AppUser user = saveUser("review-idempotent@example.com");
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{4, 5}, "image/jpeg"));
        ProposedEvent p1 = proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.of(2026, 10, 1), null,
                "review-idem-zebranie", null, null));

        EventReviewForm form = new EventReviewForm();
        form.setDecisions(List.of(accept(p1)));

        int firstAccepted = eventReviewService.applyDecisions(image.getId(), user, form);
        Instant firstResolvedAt = sourceImageRepository.findById(image.getId())
                .orElseThrow().getResolvedAt();
        assertThat(firstAccepted).isEqualTo(1);
        assertThat(firstResolvedAt).isNotNull();

        // Re-build the form from the now-ACCEPTED proposal (simulates browser back +
        // re-POST). The service must skip the non-PENDING row and leave state untouched.
        EventReviewForm replayForm = new EventReviewForm();
        replayForm.setDecisions(List.of(accept(p1)));
        int secondAccepted = eventReviewService.applyDecisions(image.getId(), user, replayForm);

        assertThat(secondAccepted).isZero();
        assertThat(eventRepository.findUpcomingByUser(user, LocalDate.of(2026, 1, 1)))
                .filteredOn(e -> "review-idem-zebranie".equals(e.getTitle()))
                .hasSize(1);
        SourceImage refreshed = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(refreshed.getResolvedAt())
                .as("resolvedAt is anchored on first decision so S-06's purge clock is stable")
                .isEqualTo(firstResolvedAt);
    }

    @Test
    void throws404WhenImageBelongsToOtherUser() {
        AppUser owner = saveUser("review-cross-owner@example.com");
        AppUser intruder = saveUser("review-cross-intruder@example.com");
        SourceImage image = sourceImageRepository.save(
                new SourceImage(owner, new byte[]{7}, "image/jpeg"));

        EventReviewForm form = new EventReviewForm();
        form.setDecisions(new ArrayList<>());

        assertThatThrownBy(() -> eventReviewService.applyDecisions(image.getId(), intruder, form))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptedEventCopiesAllFieldsFromDecisionAndTrimsStrings() {
        AppUser user = saveUser("review-fields@example.com");
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{9}, "image/jpeg"));
        ProposedEvent p1 = proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.of(2026, 11, 3), LocalTime.of(15, 30),
                "review-fields-original-title", "original requirements", "original notes"));

        ProposedEventDecision decision = new ProposedEventDecision();
        decision.setProposedEventId(p1.getId());
        decision.setAction(Action.ACCEPT);
        decision.setEventDate(LocalDate.of(2026, 11, 4));
        decision.setEventTime(LocalTime.of(16, 45));
        decision.setTitle("  review-fields-edited-title  ");
        decision.setRequirements("  yellow shirt  ");
        decision.setNotes("  bring snacks  ");

        EventReviewForm form = new EventReviewForm();
        form.setDecisions(List.of(decision));

        eventReviewService.applyDecisions(image.getId(), user, form);

        Event promoted = eventRepository.findUpcomingByUser(user, LocalDate.of(2026, 1, 1))
                .stream()
                .filter(e -> "review-fields-edited-title".equals(e.getTitle()))
                .findFirst().orElseThrow();
        assertThat(promoted.getEventDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(promoted.getEventTime()).isEqualTo(LocalTime.of(16, 45));
        assertThat(promoted.getTitle()).isEqualTo("review-fields-edited-title");
        assertThat(promoted.getRequirements()).isEqualTo("yellow shirt");
        assertThat(promoted.getNotes()).isEqualTo("bring snacks");
    }

    private AppUser saveUser(String email) {
        return UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
    }

    private static ProposedEventDecision accept(ProposedEvent p) {
        ProposedEventDecision d = new ProposedEventDecision();
        d.setProposedEventId(p.getId());
        d.setAction(Action.ACCEPT);
        d.setEventDate(p.getEventDate());
        d.setEventTime(p.getEventTime());
        d.setTitle(p.getTitle());
        d.setRequirements(p.getRequirements());
        d.setNotes(p.getNotes());
        return d;
    }

    private static ProposedEventDecision reject(ProposedEvent p) {
        ProposedEventDecision d = accept(p);
        d.setAction(Action.REJECT);
        return d;
    }
}
