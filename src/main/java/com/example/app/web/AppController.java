package com.example.app.web;

import com.example.app.event.EventRepository;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.LocalDate;

@Controller
public class AppController {

    private final AppUserRepository appUserRepository;
    private final EventRepository eventRepository;
    private final Clock clock;

    public AppController(AppUserRepository appUserRepository, EventRepository eventRepository, Clock clock) {
        this.appUserRepository = appUserRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @GetMapping("/")
    public String index(Authentication auth) {
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return "redirect:/app";
        }
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/app")
    public String app(Authentication auth, Model model) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("userEmail", user.getEmail());
        model.addAttribute("events", eventRepository.findUpcomingByUser(user, LocalDate.now(clock)));
        return "app";
    }
}
