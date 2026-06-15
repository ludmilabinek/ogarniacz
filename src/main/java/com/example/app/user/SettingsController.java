package com.example.app.user;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class SettingsController {

    private final AppUserRepository appUserRepository;
    private final IcalSubscriptionService subscriptionService;

    public SettingsController(AppUserRepository appUserRepository,
                              IcalSubscriptionService subscriptionService) {
        this.appUserRepository = appUserRepository;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/settings")
    public String settings(Authentication auth, Model model) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        String token = subscriptionService.getOrCreateToken(user);

        // Composes with server.forward-headers-strategy=framework — Fly's edge
        // sends X-Forwarded-Proto/X-Forwarded-Host, so this yields
        // https://ogarniacz.fly.dev/calendar/<token>.ics in prod and
        // http://localhost:8080/calendar/<token>.ics locally.
        String feedUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/calendar/")
                .path(token)
                .path(".ics")
                .build()
                .toUriString();

        model.addAttribute("feedUrl", feedUrl);
        model.addAttribute("userEmail", user.getEmail());
        return "settings";
    }
}
