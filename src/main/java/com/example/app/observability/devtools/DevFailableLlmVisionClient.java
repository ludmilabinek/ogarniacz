package com.example.app.observability.devtools;

import com.example.app.llm.LlmExtractionException;
import com.example.app.llm.LlmExtractionResult;
import com.example.app.llm.LlmVisionClient;
import com.example.app.llm.OpenRouterLlmVisionClient;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Dev-only wrapper around {@link OpenRouterLlmVisionClient} for the Phase 3
 * smoke endpoint. When {@link #failNextCall} is set, the next call throws
 * {@link LlmExtractionException#provider(int, String, Throwable)} with the same
 * wire shape a real {@code OpenAIServiceException} produces in
 * {@code OpenRouterLlmVisionClient} ({@code code=…  type=…  param=…}). After
 * failing once, the flag self-resets; subsequent calls delegate to the prod
 * client. Pattern mirrors {@code StubLlmVisionClient @Profile("e2e")}.
 *
 * <p>Active under {@code spring.profiles.active=dev} only — under any other
 * profile Spring skips the bean and the prod client retains {@code @Primary}.
 * The non-dev regression test {@code DevForceErrorControllerNonDevTest} pins
 * this contract.
 */
@Component
@Primary
@Profile("dev")
public class DevFailableLlmVisionClient implements LlmVisionClient {

    public static volatile boolean failNextCall = false;

    private final OpenRouterLlmVisionClient delegate;

    public DevFailableLlmVisionClient(OpenRouterLlmVisionClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException {
        if (failNextCall) {
            failNextCall = false;
            throw LlmExtractionException.provider(503, "code=forced_dev_error type=test param=?", null);
        }
        return delegate.extract(image, mimeType);
    }
}
