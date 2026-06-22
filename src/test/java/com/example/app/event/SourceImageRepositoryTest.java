package com.example.app.event;

import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class SourceImageRepositoryTest {

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    ProposedEventRepository proposedEventRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void deletingSourceImageCascadesToProposedEventChildren() {
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder,
                "source-image-cascade@example.com");
        SourceImage image = sourceImageRepository.save(
                new SourceImage(user, new byte[]{1, 2, 3}, "image/jpeg"));
        ProposedEvent child = proposedEventRepository.save(new ProposedEvent(
                image, LocalDate.of(2026, 9, 1), null,
                "cascade-test-child", null, null));

        sourceImageRepository.delete(image);
        sourceImageRepository.flush();

        assertThat(sourceImageRepository.findById(image.getId())).isEmpty();
        assertThat(proposedEventRepository.findById(child.getId())).isEmpty();
    }
}
