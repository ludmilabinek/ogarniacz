package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

@Controller
public class EventController {

    private final EventRepository eventRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    public EventController(EventRepository eventRepository, AppUserRepository appUserRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.appUserRepository = appUserRepository;
        this.clock = clock;
    }

    @GetMapping("/events/new")
    public String show(Model model) {
        if (!model.containsAttribute("eventForm")) {
            model.addAttribute("eventForm", new EventForm());
        }
        LocalDate today = LocalDate.now(clock);
        model.addAttribute("minDate", today.minusYears(5).toString());
        model.addAttribute("todayIso", today.toString());
        return "events/new";
    }

    @PostMapping("/events")
    public String create(@Valid @ModelAttribute("eventForm") EventForm form,
                         BindingResult result,
                         Authentication auth,
                         Model model) {
        if (result.hasErrors()) {
            LocalDate today = LocalDate.now(clock);
            model.addAttribute("minDate", today.minusYears(5).toString());
            model.addAttribute("todayIso", today.toString());
            return "events/new";
        }

        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        Event event = new Event(
                user,
                form.getEventDate(),
                form.getEventTime(),
                form.getTitle().trim(),
                trimOrNull(form.getRequirements()),
                trimOrNull(form.getNotes())
        );
        eventRepository.save(event);

        return "redirect:/app";
    }

    @GetMapping("/events/{id}/edit")
    public String edit(@PathVariable UUID id, Authentication auth, Model model) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        Event event = findOwnUpcomingEvent(id, user);

        EventForm form = new EventForm();
        form.setEventDate(event.getEventDate());
        form.setEventTime(event.getEventTime());
        form.setTitle(event.getTitle());
        form.setRequirements(event.getRequirements());
        form.setNotes(event.getNotes());

        LocalDate today = LocalDate.now(clock);
        model.addAttribute("eventForm", form);
        model.addAttribute("eventId", id);
        model.addAttribute("minDate", today.minusYears(5).toString());
        model.addAttribute("todayIso", today.toString());
        return "events/edit";
    }

    @PostMapping("/events/{id}")
    @Transactional
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("eventForm") EventForm form,
                         BindingResult result,
                         Authentication auth,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            LocalDate today = LocalDate.now(clock);
            model.addAttribute("eventId", id);
            model.addAttribute("minDate", today.minusYears(5).toString());
            model.addAttribute("todayIso", today.toString());
            return "events/edit";
        }

        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        Event event = findOwnUpcomingEvent(id, user);

        event.setEventDate(form.getEventDate());
        event.setEventTime(form.getEventTime());
        event.setTitle(form.getTitle().trim());
        event.setRequirements(trimOrNull(form.getRequirements()));
        event.setNotes(trimOrNull(form.getNotes()));

        ra.addFlashAttribute(
                "successMessage",
                "Zapisano zmiany w wydarzeniu „" + event.getTitle() + "”.");
        return "redirect:/app";
    }

    @PostMapping("/events/{id}/delete")
    @Transactional
    public String delete(@PathVariable UUID id, Authentication auth, RedirectAttributes ra) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        Event event = findOwnUpcomingEvent(id, user);

        String title = event.getTitle();
        eventRepository.delete(event);

        ra.addFlashAttribute("successMessage", "Usunięto wydarzenie „" + title + "”.");
        return "redirect:/app";
    }

    private Event findOwnUpcomingEvent(UUID id, AppUser user) {
        return eventRepository.findByIdAndUser(id, user)
                .filter(e -> !e.getEventDate().isBefore(LocalDate.now(clock)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
