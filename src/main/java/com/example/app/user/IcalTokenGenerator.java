package com.example.app.user;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class IcalTokenGenerator {

    // No-arg SecureRandom selects NativePRNG (/dev/urandom) on Linux —
    // non-blocking and CSPRNG-grade. NOT getInstanceStrong() (blocks on
    // /dev/random under low entropy) and NOT ThreadLocalRandom (LCG, not
    // a CSPRNG).
    private final SecureRandom rng = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String next() {
        byte[] buf = new byte[24];
        rng.nextBytes(buf);
        return encoder.encodeToString(buf);
    }
}
