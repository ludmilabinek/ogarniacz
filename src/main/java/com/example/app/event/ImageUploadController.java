package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Controller
public class ImageUploadController {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp"
    );

    private final SourceImageRepository sourceImageRepository;
    private final AppUserRepository appUserRepository;
    private final ExtractionJobRegistry jobRegistry;
    private final ExtractionService extractionService;

    public ImageUploadController(SourceImageRepository sourceImageRepository,
                                 AppUserRepository appUserRepository,
                                 ExtractionJobRegistry jobRegistry,
                                 ExtractionService extractionService) {
        this.sourceImageRepository = sourceImageRepository;
        this.appUserRepository = appUserRepository;
        this.jobRegistry = jobRegistry;
        this.extractionService = extractionService;
    }

    @GetMapping("/events/from-image")
    public String showForm(Model model) {
        return "events/from-image";
    }

    @PostMapping("/events/from-image")
    @ResponseBody
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    Authentication auth) throws IOException {
        if (file.isEmpty()) {
            return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                    "file.empty",
                    "Wybierz plik zdjęcia.");
        }

        String mimeType = resolveMimeType(file);
        if (mimeType == null) {
            return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                    "file.wrongType",
                    "Niedozwolony format. Wybierz JPG, PNG lub WEBP.");
        }

        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage saved = sourceImageRepository.save(
                new SourceImage(user, file.getBytes(), mimeType));

        UUID jobId = jobRegistry.register(saved.getId());
        extractionService.runExtraction(jobId, saved.getId());

        String statusUrl = "/events/from-image/status/" + jobId;
        String reviewUrl = "/events/from-image/" + saved.getId() + "/review";
        return ResponseEntity.ok(new UploadResponse(jobId, statusUrl, reviewUrl));
    }

    private static String resolveMimeType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && ALLOWED_MIME_TYPES.contains(declared)) {
            return declared;
        }
        String name = file.getOriginalFilename();
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return switch (ext) {
                    case ".jpg", ".jpeg" -> "image/jpeg";
                    case ".png" -> "image/png";
                    case ".webp" -> "image/webp";
                    default -> null;
                };
            }
        }
        return null;
    }

    private static ResponseEntity<UploadErrorResponse> errorResponse(HttpStatus status,
                                                                     String code,
                                                                     String message) {
        return ResponseEntity.status(status).body(new UploadErrorResponse(
                List.of(new UploadFieldError("file", code, message))));
    }
}
