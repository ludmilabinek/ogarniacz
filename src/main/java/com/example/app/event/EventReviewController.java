package com.example.app.event;

import com.example.app.event.EventReviewForm.Action;
import com.example.app.event.EventReviewForm.ProposedEventDecision;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
    private final Validator validator;

    public EventReviewController(SourceImageRepository sourceImageRepository,
                                 ProposedEventRepository proposedEventRepository,
                                 AppUserRepository appUserRepository,
                                 EventReviewService eventReviewService,
                                 Validator validator) {
        this.sourceImageRepository = sourceImageRepository;
        this.proposedEventRepository = proposedEventRepository;
        this.appUserRepository = appUserRepository;
        this.eventReviewService = eventReviewService;
        this.validator = validator;
    }

    @GetMapping("/events/from-image/{imageId}/review")
    public String review(@PathVariable UUID imageId,
                         Authentication auth,
                         Model model) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage image = sourceImageRepository.findByIdAndUser(imageId, user)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        List<ProposedEvent> proposals = proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image);

        EventReviewForm form = buildForm(proposals);

        model.addAttribute("sourceImage", image);
        if (!model.containsAttribute("reviewForm")) {
            model.addAttribute("reviewForm", form);
        }
        return "events/review";
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
