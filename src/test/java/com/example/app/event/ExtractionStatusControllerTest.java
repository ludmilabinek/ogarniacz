package com.example.app.event;

import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class ExtractionStatusControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ExtractionJobRegistry jobRegistry;

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void runningJobReturns200WithStateRunningAndNullReviewUrl() throws Exception {
        String email = "alice-status-running@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(alice, new byte[]{1}, "image/jpeg"));
        UUID jobId = jobRegistry.register(image.getId());

        mvc.perform(get("/events/from-image/status/{jobId}", jobId)
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.reviewUrl").doesNotExist())
                .andExpect(jsonPath("$.errorKind").doesNotExist())
                .andExpect(jsonPath("$.elapsedMs").isNumber());
    }

    @Test
    void doneJobReturns200WithReviewUrl() throws Exception {
        String email = "alice-status-done@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(alice, new byte[]{1}, "image/jpeg"));
        UUID jobId = jobRegistry.register(image.getId());
        jobRegistry.markDone(jobId);

        mvc.perform(get("/events/from-image/status/{jobId}", jobId)
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.reviewUrl").value(
                        "/events/from-image/" + image.getId() + "/review"));
    }

    @Test
    void failedJobReturns200WithErrorKindAndCorrelationIdAndReviewUrl() throws Exception {
        String email = "alice-status-failed@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(alice, new byte[]{1}, "image/jpeg"));
        UUID jobId = jobRegistry.register(image.getId());
        jobRegistry.markFailed(jobId, "TIMEOUT", "abc12345");

        mvc.perform(get("/events/from-image/status/{jobId}", jobId)
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.errorKind").value("TIMEOUT"))
                .andExpect(jsonPath("$.correlationId").value("abc12345"))
                .andExpect(jsonPath("$.reviewUrl").value(
                        "/events/from-image/" + image.getId() + "/review"));
    }

    @Test
    void unknownJobIdReturns404() throws Exception {
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "alice-status-unknown@example.com");

        mvc.perform(get("/events/from-image/status/{jobId}", UUID.randomUUID())
                        .with(user("alice-status-unknown@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossUserPolledJobReturns404NotForbidden() throws Exception {
        AppUser bob = UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "bob-status-cross@example.com");
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "alice-status-cross@example.com");
        SourceImage bobImage = sourceImageRepository.save(
                new SourceImage(bob, new byte[]{1}, "image/jpeg"));
        UUID jobId = jobRegistry.register(bobImage.getId());

        // Alice polls Bob's jobId — must be indistinguishable from "unknown jobId" (404),
        // not 403, to prevent jobId enumeration.
        mvc.perform(get("/events/from-image/status/{jobId}", jobId)
                        .with(user("alice-status-cross@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousStatusRedirectsToLogin() throws Exception {
        mvc.perform(get("/events/from-image/status/{jobId}", UUID.randomUUID()))
                .andExpect(status().is3xxRedirection());
    }
}
