package com.example.app.user;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IcalTokenGeneratorTest {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{32}$");

    private final IcalTokenGenerator generator = new IcalTokenGenerator();

    @Test
    void everyTokenMatchesBase64UrlNoPad32Chars() {
        for (int i = 0; i < 1000; i++) {
            String token = generator.next();
            assertThat(token)
                    .as("token %d must match ^[A-Za-z0-9_-]{32}$ (Base64URL no-padding, 24 bytes)", i)
                    .matches(TOKEN_PATTERN);
        }
    }

    @Test
    void thousandTokensAreAllUnique() {
        Set<String> seen = new HashSet<>(1000);
        for (int i = 0; i < 1000; i++) {
            seen.add(generator.next());
        }
        // At 192 bits of entropy, the probability of any collision in 1000 draws
        // is ~1.6e-52. Any duplicate is evidence of a degenerate RNG (e.g. a
        // regression to java.util.Random) and must fail the build.
        assertThat(seen)
                .as("1000 successive tokens should yield set cardinality 1000")
                .hasSize(1000);
    }
}
