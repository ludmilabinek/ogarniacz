package com.example.app.event;

import java.util.List;

public record UploadErrorResponse(List<UploadFieldError> errors) {
}
