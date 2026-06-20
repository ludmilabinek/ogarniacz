package com.example.app.event;

import com.example.app.llm.LlmExtractionException;
import com.example.app.llm.LlmExtractionResult;
import com.example.app.llm.LlmVisionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.util.UUID;

/**
 * Async seam wrapping the blocking {@link LlmVisionClient#extract}. Lives in a
 * separate bean from {@code ImageUploadController} because Spring's {@code @Async}
 * proxy doesn't intercept self-invocations. On success, persists one
 * {@link ProposedEvent} per extracted event. On any failure (LLM-side or
 * surrounding plumbing), stamps {@code lastErrorKind} + {@code correlationId} on
 * the source image and marks the registry entry FAILED so the polling JS can
 * route to the error template.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final LlmVisionClient llmVisionClient;
    private final SourceImageRepository sourceImageRepository;
    private final ProposedEventRepository proposedEventRepository;
    private final ExtractionJobRegistry jobRegistry;

    public ExtractionService(LlmVisionClient llmVisionClient,
                             SourceImageRepository sourceImageRepository,
                             ProposedEventRepository proposedEventRepository,
                             ExtractionJobRegistry jobRegistry) {
        this.llmVisionClient = llmVisionClient;
        this.sourceImageRepository = sourceImageRepository;
        this.proposedEventRepository = proposedEventRepository;
        this.jobRegistry = jobRegistry;
    }

    @Async("extractionExecutor")
    public void runExtraction(UUID jobId, UUID imageId) {
        SourceImage image = sourceImageRepository.findById(imageId).orElse(null);
        if (image == null) {
            String correlationId = newCorrelationId();
            log.error("extraction_failed imageId={} correlationId={} kind=UNEXPECTED reason=image-missing",
                    imageId, correlationId);
            jobRegistry.markFailed(jobId, "UNEXPECTED", correlationId);
            return;
        }
        try {
            LlmExtractionResult result = llmVisionClient.extract(
                    image.getData(), MimeType.valueOf(image.getMimeType()));
            for (LlmExtractionResult.ProposedEvent p : result.proposedEvents()) {
                proposedEventRepository.save(new ProposedEvent(
                        image, p.date(), p.time(), p.title(), p.requirements(), p.notes()));
            }
            jobRegistry.markDone(jobId);
        } catch (LlmExtractionException ex) {
            String correlationId = newCorrelationId();
            image.setLastErrorKind(ex.kind().name());
            image.setCorrelationId(correlationId);
            sourceImageRepository.save(image);
            log.error("extraction_failed imageId={} correlationId={} kind={} httpStatus={} providerMessage={}",
                    imageId, correlationId, ex.kind(), ex.httpStatus(), ex.providerMessage());
            jobRegistry.markFailed(jobId, ex.kind().name(), correlationId);
        } catch (RuntimeException ex) {
            // OpenRouterLlmVisionClient already wraps every model-side RuntimeException
            // into LlmExtractionException at its top-level catch, so this branch covers
            // failures around the LLM call: image reload, ProposedEvent persistence,
            // registry update.
            String correlationId = newCorrelationId();
            image.setLastErrorKind("UNEXPECTED");
            image.setCorrelationId(correlationId);
            sourceImageRepository.save(image);
            log.error("extraction_failed imageId={} correlationId={} kind=UNEXPECTED",
                    imageId, correlationId, ex);
            jobRegistry.markFailed(jobId, "UNEXPECTED", correlationId);
        }
    }

    private static String newCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
