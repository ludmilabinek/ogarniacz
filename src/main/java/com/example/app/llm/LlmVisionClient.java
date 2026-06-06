package com.example.app.llm;

import org.springframework.util.MimeType;

/**
 * Single seam between the app and the vision model.
 *
 * <p>Caller is responsible for downscaling. Size limits depend on the model and provider — typical
 * phone photos (2–5 MB) pass through OpenRouter without issue, but larger uploads should be
 * downscaled before this call or the provider may reject them with a 4xx. F-01 does not validate
 * at the boundary; that's a callsite concern (S-05's upload pipeline).
 */
public interface LlmVisionClient {

	LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException;
}
