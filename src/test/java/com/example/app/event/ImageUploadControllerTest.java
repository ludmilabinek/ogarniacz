package com.example.app.event;

import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class ImageUploadControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void anonymousGetUploadFormRedirectsToLogin() throws Exception {
        mvc.perform(get("/events/from-image"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void authenticatedGetRendersUploadForm() throws Exception {
        mvc.perform(get("/events/from-image")
                        .with(user("alice-from-image-form@example.com")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/events/from-image\"")))
                .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
                .andExpect(content().string(containsString("accept=\"image/jpeg,image/png,image/webp\"")));
    }

    @Test
    void postValidJpegPersistsSourceImageAndReturnsReviewUrl() throws Exception {
        String email = "alice-from-image-happy@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);

        byte[] jpeg = tinyJpegBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg);

        long before = sourceImageRepository.count();

        MvcResult result = mvc.perform(multipart("/events/from-image")
                        .file(file)
                        .with(user(email))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reviewUrl").exists())
                .andReturn();

        long after = sourceImageRepository.count();
        assertThat(after).isEqualTo(before + 1);

        String body = result.getResponse().getContentAsString();
        assertThat(body).matches("\\{\"reviewUrl\":\"/events/from-image/[0-9a-f-]+/review\"\\}");

        // Verify the persisted row carries Alice's bytes + mime.
        List<SourceImage> all = sourceImageRepository.findAll();
        SourceImage persisted = all.stream()
                .filter(si -> si.getUser().getId().equals(alice.getId()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(persisted.getMimeType()).isEqualTo("image/jpeg");
        assertThat(persisted.getData()).isEqualTo(jpeg);
    }

    @Test
    void postEmptyFileReturns422WithFileEmptyCode() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[0]);

        mvc.perform(multipart("/events/from-image")
                        .file(empty)
                        .with(user("alice-from-image-empty@example.com"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].code").value("file.empty"));
    }

    @Test
    void postWrongMimeTypeReturns422WithFileWrongTypeCode() throws Exception {
        MockMultipartFile txt = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());

        mvc.perform(multipart("/events/from-image")
                        .file(txt)
                        .with(user("alice-from-image-wrongtype@example.com"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].code").value("file.wrongType"));
    }

    @Test
    void postPdfWithExtensionFallbackIsAlsoRejected() throws Exception {
        // Explicit null content-type forces the extension-fallback path
        // (parents of iOS clients sometimes upload with empty Content-Type).
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "report.pdf", null, new byte[] {1, 2, 3});

        mvc.perform(multipart("/events/from-image")
                        .file(pdf)
                        .with(user("alice-from-image-pdf@example.com"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("file.wrongType"));
    }

    @Test
    void postPngWithNullMimeButValidExtensionIsAccepted() throws Exception {
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "alice-from-image-extfallback@example.com");
        // contentType=null but filename ends in .png — should pass via extension fallback.
        MockMultipartFile png = new MockMultipartFile(
                "file", "photo.PNG", null, tinyPngBytes());

        mvc.perform(multipart("/events/from-image")
                        .file(png)
                        .with(user("alice-from-image-extfallback@example.com"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewUrl").exists());
    }

    @Test
    void postWithoutCsrfIs403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.jpg", MediaType.IMAGE_JPEG_VALUE, tinyJpegBytes());

        mvc.perform(multipart("/events/from-image")
                        .file(file)
                        .with(user("alice-from-image-nocsrf@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousPostRedirectsToLogin() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.jpg", MediaType.IMAGE_JPEG_VALUE, tinyJpegBytes());

        mvc.perform(multipart("/events/from-image")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void getReviewStubRendersImageId() throws Exception {
        String email = "alice-review-stub@example.com";
        AppUser alice = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = sourceImageRepository.save(
                new SourceImage(alice, tinyJpegBytes(), "image/jpeg"));

        mvc.perform(get("/events/from-image/" + image.getId() + "/review")
                        .with(user(email)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(image.getId().toString())))
                .andExpect(content().string(containsString("faza 3")));
    }

    @Test
    void getReviewStubCrossUserIs404() throws Exception {
        AppUser bob = UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "bob-review-cross@example.com");
        UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "alice-review-cross@example.com");
        SourceImage bobImage = sourceImageRepository.save(
                new SourceImage(bob, tinyJpegBytes(), "image/jpeg"));

        mvc.perform(get("/events/from-image/" + bobImage.getId() + "/review")
                        .with(user("alice-review-cross@example.com")))
                .andExpect(status().isNotFound());
    }

    static byte[] tinyJpegBytes() {
        try {
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build tiny JPEG fixture", e);
        }
    }

    static byte[] tinyPngBytes() {
        try {
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build tiny PNG fixture", e);
        }
    }
}
