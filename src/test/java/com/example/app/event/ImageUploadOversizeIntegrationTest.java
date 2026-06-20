package com.example.app.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin the load-bearing regression: a real >15MB multipart POST goes through the full
 * SecurityFilterChain (CsrfFilter triggers Tomcat's lazy multipart parse), and the
 * Tomcat-native InvalidParameterException (cause = FileSizeLimitExceededException) must
 * still surface as the same JSON 413 envelope the JS layer expects. MockMvc bypasses
 * the real multipart parser, so a unit-level test of the handler alone cannot catch this.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class ImageUploadOversizeIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void oversizedMultipartReturnsCustomJsonEnvelopeWithoutStacktrace() throws Exception {
        byte[] payload = new byte[16 * 1024 * 1024]; // 16 MB — above the 15 MB ceiling
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }

        String boundary = "----test-boundary-" + Long.toHexString(System.nanoTime());
        byte[] body = buildMultipartBody(boundary, "file", "over.jpg", "image/jpeg", payload);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events/from-image"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");

        String responseBody = response.body();
        assertThat(responseBody).contains("\"field\":\"file\"");
        assertThat(responseBody).contains("\"code\":\"file.tooLarge\"");
        assertThat(responseBody).contains("Zdjęcie jest za duże");

        // Negative guards: no Tomcat / Spring internals leak to the client.
        assertThat(responseBody).doesNotContain("\"trace\"");
        assertThat(responseBody).doesNotContain("exceeds its maximum permitted size");
        assertThat(responseBody).doesNotContain("FileSizeLimitExceededException");
        assertThat(responseBody).doesNotContain("InvalidParameterException");
    }

    private static byte[] buildMultipartBody(String boundary, String fieldName, String filename,
                                             String contentType, byte[] fileBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 512);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
