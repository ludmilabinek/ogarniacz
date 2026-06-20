package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class EventReviewController {

    private final SourceImageRepository sourceImageRepository;
    private final AppUserRepository appUserRepository;

    public EventReviewController(SourceImageRepository sourceImageRepository,
                                 AppUserRepository appUserRepository) {
        this.sourceImageRepository = sourceImageRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/events/from-image/{imageId}/review")
    public String review(@PathVariable UUID imageId,
                         Authentication auth,
                         Model model) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage image = sourceImageRepository.findByIdAndUser(imageId, user)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        model.addAttribute("sourceImageId", image.getId());
        return "events/review";
    }
}
