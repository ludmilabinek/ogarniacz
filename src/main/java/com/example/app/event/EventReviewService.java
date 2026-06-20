package com.example.app.event;

import com.example.app.event.EventReviewForm.Action;
import com.example.app.event.EventReviewForm.ProposedEventDecision;
import com.example.app.event.ProposedEvent.ProposedEventStatus;
import com.example.app.user.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventReviewService {

    private final SourceImageRepository sourceImageRepository;
    private final ProposedEventRepository proposedEventRepository;
    private final EventRepository eventRepository;
    private final Clock clock;

    public EventReviewService(SourceImageRepository sourceImageRepository,
                              ProposedEventRepository proposedEventRepository,
                              EventRepository eventRepository,
                              Clock clock) {
        this.sourceImageRepository = sourceImageRepository;
        this.proposedEventRepository = proposedEventRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Transactional
    public int applyDecisions(UUID imageId, AppUser user, EventReviewForm form) {
        SourceImage image = sourceImageRepository.findByIdAndUser(imageId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<ProposedEvent> proposals = proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image);
        Map<UUID, ProposedEvent> byId = new HashMap<>();
        for (ProposedEvent p : proposals) {
            byId.put(p.getId(), p);
        }

        Instant now = Instant.now(clock);
        int acceptedCount = 0;
        for (ProposedEventDecision decision : form.getDecisions()) {
            ProposedEvent proposal = byId.get(decision.getProposedEventId());
            if (proposal == null || proposal.getStatus() != ProposedEventStatus.PENDING) {
                continue;
            }
            if (decision.getAction() == Action.ACCEPT) {
                Event event = new Event(
                        user,
                        decision.getEventDate(),
                        decision.getEventTime(),
                        decision.getTitle().trim(),
                        trimOrNull(decision.getRequirements()),
                        trimOrNull(decision.getNotes()));
                eventRepository.save(event);
                proposal.setStatus(ProposedEventStatus.ACCEPTED);
                proposal.setDecidedAt(now);
                acceptedCount++;
            } else {
                proposal.setStatus(ProposedEventStatus.REJECTED);
                proposal.setDecidedAt(now);
            }
        }

        // Guard preserves the first-decision timestamp on replay so S-06's purge
        // clock anchors on the original resolution, not the latest re-submit.
        // No explicit save() — image is managed inside @Transactional; dirty-checking
        // flushes on commit (see lessons.md "Inside @Transactional ... explicit save() is redundant").
        if (image.getResolvedAt() == null) {
            image.setResolvedAt(now);
        }

        return acceptedCount;
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
