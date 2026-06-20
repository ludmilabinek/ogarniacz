package com.example.app.event;

import java.util.UUID;

public record UploadResponse(UUID jobId, String statusUrl, String reviewUrl) {
}
