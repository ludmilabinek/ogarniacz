package com.example.app.event;

import com.example.app.event.EventReviewForm.Action;
import com.example.app.event.EventReviewForm.ProposedEventDecision;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class EventReviewController {

    private final SourceImageRepository sourceImageRepository;
    private final ProposedEventRepository proposedEventRepository;
    private final AppUserRepository appUserRepository;
    private final EventReviewService eventReviewService;
    private final ExtractionJobRegistry jobRegistry;
    private final ExtractionService extractionService;
    private final Validator validator;

    public EventReviewController(SourceImageRepository sourceImageRepository,
                                 ProposedEventRepository proposedEventRepository,
                                 AppUserRepository appUserRepository,
                                 EventReviewService eventReviewService,
                                 ExtractionJobRegistry jobRegistry,
                                 ExtractionService extractionService,
                                 Validator validator) {
        this.sourceImageRepository = sourceImageRepository;
        this.proposedEventRepository = proposedEventRepository;
        this.appUserRepository = appUserRepository;
        this.eventReviewService = eventReviewService;
        this.jobRegistry = jobRegistry;
        this.extractionService = extractionService;
        this.validator = validator;
    }

    /**
     * Renders the review page for a previously uploaded image.
     *
     * <p>404 contract — two cases collapse to the same response on purpose:
     * <ul>
     *   <li>image belongs to a different user (do not leak existence — return 404, not 403);</li>
     *   <li>image row is gone (deleted, or {@link ExtractionService} ran for an unpersisted id).
     *       The hosted localized error template ({@code events/review-error}) is only reachable
     *       when the row exists with a {@code lastErrorKind}; an orphaned poll falls through to
     *       the generic 404 page. This is acceptable at the current MVP scale (no concurrent
     *       delete path); revisit when S-06 purge or any other flow can delete {@link SourceImage}
     *       rows while extraction is in flight — at that point surface a synthetic
     *       {@code errorKind=IMAGE_GONE} via the status response so the polling client lands
     *       on {@code review-error} instead of a 404.</li>
     * </ul>
     */
    @GetMapping("/events/from-image/{imageId}/review")
    public String review(@PathVariable UUID imageId,
                         Authentication auth,
                         Model model) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage image = sourceImageRepository.findByIdAndUser(imageId, user)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        List<ProposedEvent> proposals = proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image);

        model.addAttribute("sourceImage", image);

        // Branch ordering matters (see plan §4b "Critical Implementation Details"):
        // error first → running before empty → empty → happy. A failed extraction
        // without proposals must show the error page, not the empty page; a
        // still-extracting image must show the wait page, not the empty page.
        boolean hasError = image.getLastErrorKind() != null && image.getResolvedAt() == null;
        if (hasError) {
            model.addAttribute("errorKind", image.getLastErrorKind());
            model.addAttribute("correlationId", image.getCorrelationId());
            return "events/review-error";
        }

        boolean noProposals = proposals.isEmpty();
        if (noProposals && image.getResolvedAt() == null
                && jobRegistry.findRunningByImageId(imageId).isPresent()) {
            return "events/review-running";
        }

        if (noProposals) {
            return "events/review-empty";
        }

        if (!model.containsAttribute("reviewForm")) {
            model.addAttribute("reviewForm", buildForm(proposals));
        }
        return "events/review";
    }

    @PostMapping("/events/from-image/{imageId}/retry")
    @ResponseBody
    public ResponseEntity<UploadResponse> retry(@PathVariable UUID imageId,
                                                Authentication auth) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage image = sourceImageRepository.findByIdAndUser(imageId, user)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        image.setLastErrorKind(null);
        image.setCorrelationId(null);
        sourceImageRepository.save(image);

        UUID jobId = jobRegistry.register(imageId);
        extractionService.runExtraction(jobId, imageId);

        String statusUrl = "/events/from-image/status/" + jobId;
        String reviewUrl = "/events/from-image/" + imageId + "/review";
        return ResponseEntity.ok(new UploadResponse(jobId, statusUrl, reviewUrl));
    }

    @PostMapping("/events/from-image/{imageId}/decisions")
    public String submitDecisions(@PathVariable UUID imageId,
                                  @ModelAttribute("reviewForm") EventReviewForm form,
                                  Authentication auth,
                                  Model model,
                                  RedirectAttributes redirectAttributes) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage image = sourceImageRepository.findByIdAndUser(imageId, user)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        BindingResult bindingResult = new BeanPropertyBindingResult(form, "reviewForm");
        validateAcceptRows(form, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("sourceImage", image);
            model.addAttribute("reviewForm", form);
            model.addAttribute(
                    "org.springframework.validation.BindingResult.reviewForm",
                    bindingResult);
            return "events/review";
        }

        int accepted = eventReviewService.applyDecisions(imageId, user, form);
        redirectAttributes.addFlashAttribute(
                "successMessage", "Dodano " + accepted + " wydarzeń.");
        return "redirect:/app";
    }

    private EventReviewForm buildForm(List<ProposedEvent> proposals) {
        EventReviewForm form = new EventReviewForm();
        List<ProposedEventDecision> decisions = new ArrayList<>();
        for (ProposedEvent p : proposals) {
            ProposedEventDecision decision = new ProposedEventDecision();
            decision.setProposedEventId(p.getId());
            decision.setAction(Action.ACCEPT);
            decision.setEventDate(p.getEventDate());
            decision.setEventTime(p.getEventTime());
            decision.setTitle(p.getTitle());
            decision.setRequirements(p.getRequirements());
            decision.setNotes(p.getNotes());
            decision.setStatus(p.getStatus().name());
            decisions.add(decision);
        }
        form.setDecisions(decisions);
        return form;
    }

    private void validateAcceptRows(EventReviewForm form, BindingResult bindingResult) {
        List<ProposedEventDecision> decisions = form.getDecisions();
        for (int i = 0; i < decisions.size(); i++) {
            ProposedEventDecision decision = decisions.get(i);
            if (decision.getAction() != Action.ACCEPT) {
                continue;
            }
            Set<ConstraintViolation<ProposedEventDecision>> violations = validator.validate(decision);
            for (ConstraintViolation<ProposedEventDecision> v : violations) {
                String field = "decisions[" + i + "]." + v.getPropertyPath();
                bindingResult.addError(new FieldError(
                        "reviewForm",
                        field,
                        v.getInvalidValue(),
                        false,
                        null,
                        null,
                        v.getMessage()));
            }
        }
    }
}
