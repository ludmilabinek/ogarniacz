package com.example.app.llm;

public final class LlmExtractionException extends RuntimeException {

	public enum Kind {
		TIMEOUT,
		PROVIDER_ERROR,
		MALFORMED_RESPONSE
	}

	private final Kind kind;
	private final Integer httpStatus;
	private final String providerMessage;

	private LlmExtractionException(Kind kind, Integer httpStatus, String providerMessage, Throwable cause) {
		super("LLM extraction failed: " + kind + (httpStatus != null ? " (HTTP " + httpStatus + ")" : ""), cause);
		this.kind = kind;
		this.httpStatus = httpStatus;
		this.providerMessage = providerMessage;
	}

	public static LlmExtractionException timeout(Throwable cause) {
		return new LlmExtractionException(Kind.TIMEOUT, null, null, cause);
	}

	public static LlmExtractionException provider(int httpStatus, String providerMessage, Throwable cause) {
		return new LlmExtractionException(Kind.PROVIDER_ERROR, httpStatus, providerMessage, cause);
	}

	public static LlmExtractionException malformed(Throwable cause) {
		return new LlmExtractionException(Kind.MALFORMED_RESPONSE, null, null, cause);
	}

	public Kind kind() {
		return kind;
	}

	public Integer httpStatus() {
		return httpStatus;
	}

	public String providerMessage() {
		return providerMessage;
	}
}
