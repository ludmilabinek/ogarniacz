package com.example.app.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Opt-in deterministic {@link Clock} for {@code @SpringBootTest} classes that need
 * to assert against {@code Instant.now(clock)}. Activate per test class with
 * {@code @Import(FixedClockTestConfig.class)}; non-importing tests keep the
 * production {@code Clock.system(...)} bean.
 *
 * <p>Chosen over {@code @MockitoBean Clock} because the bean override would
 * propagate to every other {@code Clock} consumer in the context
 * ({@code EventReviewService}, {@code ExtractionJobRegistry}, {@code CalendarController})
 * — many of which assert on real time — and fragment the Spring context cache
 * per test class.
 *
 * <p>Seeds the deterministic-clock pattern that {@code test-plan.md §6.9} will
 * codify alongside the {@code @Scheduled} test pattern.
 */
@TestConfiguration
public class FixedClockTestConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T12:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
