package com.example.app.event;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaxUploadSizeExceededHandlerTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final MaxUploadSizeExceededHandler filter =
            new MaxUploadSizeExceededHandler("15MB", mapper);

    @Test
    void writesJson413EnvelopeWhenSpringMaxUploadSizeBubblesOnImagePath() throws Exception {
        MockHttpServletRequest req = imageUploadRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = throwingChain(new MaxUploadSizeExceededException(15L * 1024 * 1024));

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getContentType()).startsWith("application/json");
        JsonNode body = mapper.readTree(resp.getContentAsString());
        assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("file");
        assertThat(body.get("errors").get(0).get("code").asText()).isEqualTo("file.tooLarge");
        assertThat(body.get("errors").get(0).get("message").asText()).contains("15MB");
    }

    @Test
    void writesJson413EnvelopeWhenTomcatInvalidParameterCausedBySizeOnImagePath() throws Exception {
        MockHttpServletRequest req = imageUploadRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = throwingChain(invalidParameterWithSizeCause());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getContentAsString()).contains("\"code\":\"file.tooLarge\"");
    }

    @Test
    void writesJson413EnvelopeWhenSizeCauseIsWrappedInServletException() throws Exception {
        MockHttpServletRequest req = imageUploadRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ServletException wrapped = new ServletException("filter chain failure",
                invalidParameterWithSizeCause());
        FilterChain chain = throwingChain(wrapped);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getContentAsString()).contains("\"code\":\"file.tooLarge\"");
    }

    @Test
    void rethrowsSpringMaxUploadSizeForPathsOutsideImageUpload() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/some/other/multipart");
        req.setRequestURI("/some/other/multipart");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(1024);

        assertThatThrownBy(() -> filter.doFilter(req, resp, throwingChain(ex)))
                .isSameAs(ex);
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void rethrowsTomcatInvalidParameterForPathsOutsideImageUpload() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setRequestURI("/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        InvalidParameterException ex = invalidParameterWithSizeCause();

        assertThatThrownBy(() -> filter.doFilter(req, resp, throwingChain(ex)))
                .isSameAs(ex);
    }

    @Test
    void rethrowsTomcatInvalidParameterOnImagePathWithoutSizeCause() {
        MockHttpServletRequest req = imageUploadRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        InvalidParameterException ex = new InvalidParameterException("parameter parse failed");

        assertThatThrownBy(() -> filter.doFilter(req, resp, throwingChain(ex)))
                .isSameAs(ex);
    }

    @Test
    void passesThroughWhenChainCompletesNormally() throws Exception {
        MockHttpServletRequest req = imageUploadRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain noop = new MockFilterChain();

        filter.doFilter(req, resp, noop);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString()).isEmpty();
    }

    private static MockHttpServletRequest imageUploadRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/events/from-image");
        req.setRequestURI("/events/from-image");
        return req;
    }

    private static FilterChain throwingChain(Throwable t) {
        return (request, response) -> {
            sneakyThrow(t);
        };
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private static InvalidParameterException invalidParameterWithSizeCause() {
        FileSizeLimitExceededException sizeEx =
                new FileSizeLimitExceededException("over limit", 16_000_000, 15_728_640);
        return new InvalidParameterException("multipart parameter parse failed", sizeEx);
    }
}
