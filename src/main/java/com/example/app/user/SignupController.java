package com.example.app.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Locale;

@Controller
public class SignupController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final RememberMeServices rememberMeServices;
    private final SecurityContextRepository securityContextRepository;

    public SignupController(AppUserRepository appUserRepository,
                            PasswordEncoder passwordEncoder,
                            RememberMeServices rememberMeServices,
                            SecurityContextRepository securityContextRepository) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.rememberMeServices = rememberMeServices;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/signup")
    public String show(Model model) {
        if (!model.containsAttribute("signupForm")) {
            model.addAttribute("signupForm", new SignupForm());
        }
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form,
                         BindingResult result,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        if (result.hasErrors()) {
            return "signup";
        }

        form.setEmail(form.getEmail().toLowerCase(Locale.ROOT).trim());

        if (appUserRepository.existsByEmail(form.getEmail())) {
            result.rejectValue("email", "duplicate", "Email already in use.");
            return "signup";
        }

        String hash = passwordEncoder.encode(form.getPassword());
        AppUser saved;
        try {
            saved = appUserRepository.saveAndFlush(new AppUser(form.getEmail(), hash));
        } catch (DataIntegrityViolationException dup) {
            result.rejectValue("email", "duplicate", "Email already in use.");
            return "signup";
        }

        // Keep authorities = List.of() in sync with AppUserDetailsService.
        UserDetails userDetails = User.withUsername(saved.getEmail())
                .password(saved.getPasswordHash())
                .authorities(List.of())
                .build();
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                userDetails, null, userDetails.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        rememberMeServices.loginSuccess(request, response, auth);

        return "redirect:/app";
    }
}
