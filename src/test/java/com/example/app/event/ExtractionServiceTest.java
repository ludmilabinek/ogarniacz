package com.example.app.event;

import com.example.app.event.ExtractionJobRegistry.JobState;
import com.example.app.event.ExtractionJobRegistry.JobStatusEntry;
import com.example.app.event.ProposedEvent.ProposedEventStatus;
import com.example.app.llm.LlmExtractionException;
import com.example.app.llm.LlmExtractionResult;
import com.example.app.llm.LlmVisionClient;
import com.example.app.testsupport.FixedClockTestConfig;
import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(FixedClockTestConfig.class)
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
@ExtendWith(OutputCaptureExtension.class)
class ExtractionServiceTest {

    @Autowired
    ExtractionService extractionService;

    @Autowired
    ExtractionJobRegistry jobRegistry;

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    ProposedEventRepository proposedEventRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    LlmVisionClient llmVisionClient;

    @Test
    void successWithTwoEventsPersistsTwoPendingProposalsAndMarksDone() {
        SourceImage image = persistImage("alice-extract-2events@example.com");
        UUID jobId = jobRegistry.register(image.getId());
        LlmExtractionResult result = new LlmExtractionResult(List.of(
                new LlmExtractionResult.ProposedEvent(
                        LocalDate.of(2026, 9, 1), LocalTime.of(9, 0),
                        "Rozpoczęcie roku", "strój galowy", "sala 12"),
                new LlmExtractionResult.ProposedEvent(
                        LocalDate.of(2026, 9, 15), null,
                        "Zebranie rodziców", null, null)),
                "[…]", 42L);
        when(llmVisionClient.extract(any(), any())).thenReturn(result);

        extractionService.runExtraction(jobId, image.getId());

        awaitTerminal(jobId);
        JobStatusEntry entry = jobRegistry.get(jobId).orElseThrow();
        assertThat(entry.state()).isEqualTo(JobState.DONE);
        assertThat(entry.errorKind()).isNull();
        assertThat(entry.correlationId()).isNull();

        List<ProposedEvent> proposals = proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image);
        assertThat(proposals).hasSize(2);
        assertThat(proposals).allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(ProposedEventStatus.PENDING));
        assertThat(proposals.get(0).getTitle()).isEqualTo("Rozpoczęcie roku");
        assertThat(proposals.get(0).getEventTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(proposals.get(0).getRequirements()).isEqualTo("strój galowy");
        assertThat(proposals.get(0).getNotes()).isEqualTo("sala 12");
        assertThat(proposals.get(1).getTitle()).isEqualTo("Zebranie rodziców");
        assertThat(proposals.get(1).getEventTime()).isNull();

        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isNull();
        assertThat(reloaded.getCorrelationId()).isNull();
        assertThat(reloaded.getResolvedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT);
    }

    @Test
    void successWithEmptyListPersistsZeroRowsAndMarksDoneAndStampsResolvedAt() {
        SourceImage image = persistImage("alice-extract-empty@example.com");
        UUID jobId = jobRegistry.register(image.getId());
        when(llmVisionClient.extract(any(), any()))
                .thenReturn(new LlmExtractionResult(List.of(), "[]", 10L));

        extractionService.runExtraction(jobId, image.getId());

        awaitTerminal(jobId);
        assertThat(jobRegistry.get(jobId).orElseThrow().state()).isEqualTo(JobState.DONE);
        assertThat(proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image))
                .isEmpty();
        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isNull();
        assertThat(reloaded.getResolvedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT);
    }

    @Test
    void timeoutMarksFailedAndStampsLastErrorKindWithCorrelationId() {
        SourceImage image = persistImage("alice-extract-timeout@example.com");
        UUID jobId = jobRegistry.register(image.getId());
        when(llmVisionClient.extract(any(), any()))
                .thenThrow(LlmExtractionException.timeout(new java.net.SocketTimeoutException("read")));

        extractionService.runExtraction(jobId, image.getId());

        awaitTerminal(jobId);
        JobStatusEntry entry = jobRegistry.get(jobId).orElseThrow();
        assertThat(entry.state()).isEqualTo(JobState.FAILED);
        assertThat(entry.errorKind()).isEqualTo("TIMEOUT");
        assertThat(entry.correlationId()).isNotBlank();

        assertThat(proposedEventRepository
                .findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(image))
                .isEmpty();

        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isEqualTo("TIMEOUT");
        assertThat(reloaded.getCorrelationId()).isEqualTo(entry.correlationId());
        assertThat(reloaded.getResolvedAt()).isNull();
    }

    @Test
    void providerErrorMarksFailedAndLogsHttpStatusAndProviderMessage(CapturedOutput output) {
        SourceImage image = persistImage("alice-extract-provider@example.com");
        UUID jobId = jobRegistry.register(image.getId());
        when(llmVisionClient.extract(any(), any()))
                .thenThrow(LlmExtractionException.provider(503, "upstream busy", null));

        extractionService.runExtraction(jobId, image.getId());

        awaitTerminal(jobId);
        JobStatusEntry entry = jobRegistry.get(jobId).orElseThrow();
        assertThat(entry.state()).isEqualTo(JobState.FAILED);
        assertThat(entry.errorKind()).isEqualTo("PROVIDER_ERROR");
        assertThat(entry.correlationId()).isNotBlank();

        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isEqualTo("PROVIDER_ERROR");
        assertThat(reloaded.getResolvedAt()).isNull();

        assertThat(output.getAll()).contains("503");
        assertThat(output.getAll()).contains("upstream busy");
        assertThat(output.getAll()).contains(entry.correlationId());
    }

    @Test
    void malformedResponseMarksFailedWithMalformedKind() {
        SourceImage image = persistImage("alice-extract-malformed@example.com");
        UUID jobId = jobRegistry.register(image.getId());
        when(llmVisionClient.extract(any(), any()))
                .thenThrow(LlmExtractionException.malformed(new RuntimeException("not json")));

        extractionService.runExtraction(jobId, image.getId());

        awaitTerminal(jobId);
        JobStatusEntry entry = jobRegistry.get(jobId).orElseThrow();
        assertThat(entry.state()).isEqualTo(JobState.FAILED);
        assertThat(entry.errorKind()).isEqualTo("MALFORMED_RESPONSE");
        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isEqualTo("MALFORMED_RESPONSE");
        assertThat(reloaded.getResolvedAt()).isNull();
    }

    @Test
    void unexpectedRuntimeExceptionMarksFailedAndDoesNotStampResolvedAt() {
        SourceImage image = persistImage("alice-extract-unexpected@example.com");
        UUID jobId = jobRegistry.register(image.getId());
        when(llmVisionClient.extract(any(), any()))
                .thenThrow(new RuntimeException("synthetic non-Llm failure"));

        extractionService.runExtraction(jobId, image.getId());

        awaitTerminal(jobId);
        JobStatusEntry entry = jobRegistry.get(jobId).orElseThrow();
        assertThat(entry.state()).isEqualTo(JobState.FAILED);
        assertThat(entry.errorKind()).isEqualTo("UNEXPECTED");
        assertThat(entry.correlationId()).isNotBlank();

        SourceImage reloaded = sourceImageRepository.findById(image.getId()).orElseThrow();
        assertThat(reloaded.getLastErrorKind()).isEqualTo("UNEXPECTED");
        assertThat(reloaded.getResolvedAt()).isNull();
    }

    private SourceImage persistImage(String email) {
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        return sourceImageRepository.save(new SourceImage(user, new byte[]{1, 2, 3}, "image/jpeg"));
    }

    private void awaitTerminal(UUID jobId) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    JobStatusEntry entry = jobRegistry.get(jobId).orElse(null);
                    return entry != null && entry.state() != JobState.RUNNING;
                });
    }
}
