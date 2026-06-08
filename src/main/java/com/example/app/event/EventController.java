package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class EventController {

    private final EventRepository eventRepository;
    private final AppUserRepository appUserRepository;

    public EventController(EventRepository eventRepository, AppUserRepository appUserRepository) {
        this.eventRepository = eventRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/events/new")
    public String show(Model model) {
        if (!model.containsAttribute("eventForm")) {
            model.addAttribute("eventForm", new EventForm());
        }
        return "events/new";
    }

    @PostMapping("/events")
    public String create(@Valid @ModelAttribute("eventForm") EventForm form,
                         BindingResult result,
                         Authentication auth) {
        if (result.hasErrors()) {
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

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
