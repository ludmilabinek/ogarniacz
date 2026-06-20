package com.example.app.event;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * Surfaces upload-size violations on /events/from-image* as the same JSON 413
 * envelope the upload form's JS layer expects, regardless of where in the filter
 * chain the size error originates.
 *
 * <p>This is a {@link OncePerRequestFilter} (not a {@code @ControllerAdvice}) because
 * Spring Security's {@code CsrfFilter} calls {@code request.getParameter("_csrf")} on
 * every multipart POST, which triggers Tomcat's lazy multipart parse before Spring's
 * {@code MultipartResolver} runs. The size limit then fires inside the filter chain —
 * the request never reaches the {@code DispatcherServlet}, so no
 * {@code @ExceptionHandler} resolver is ever consulted. The exception escapes to the
 * container and lands on {@code BasicErrorController} with an English default body
 * and a stacktrace, defeating the custom Polish envelope.
 *
 * <p>Filter is ordered at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps the security
 * chain. A path guard keeps unrelated multipart endpoints on Spring's default
 * error handling.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaxUploadSizeExceededHandler extends OncePerRequestFilter {

    private static final String GUARDED_PATH_PREFIX = "/events/from-image";

    private final String maxFileSize;
    private final ObjectMapper objectMapper;

    public MaxUploadSizeExceededHandler(
            @Value("${spring.servlet.multipart.max-file-size}") String maxFileSize,
            ObjectMapper objectMapper) {
        this.maxFileSize = maxFileSize;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            if (isImageUploadPath(request) && hasSizeLimitCause(ex)) {
                writeOverSizeEnvelope(response);
                return;
            }
            throw ex;
        }
    }

    void writeOverSizeEnvelope(HttpServletResponse response) throws IOException {
        String message = "Zdjęcie jest za duże. Maksimum to " + maxFileSize
                + ". Zmniejsz rozdzielczość w aparacie albo zrób screenshot z mniejszą jakością.";
        UploadErrorResponse body = new UploadErrorResponse(List.of(
                new UploadFieldError("file", "file.tooLarge", message)));
        response.reset();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static boolean isImageUploadPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(GUARDED_PATH_PREFIX);
    }

    static boolean hasSizeLimitCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof MaxUploadSizeExceededException
                    || c instanceof FileSizeLimitExceededException) {
                return true;
            }
        }
        return false;
    }
}
