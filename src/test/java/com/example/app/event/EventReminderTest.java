package com.example.app.event;

import com.example.app.user.AppUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EventReminderTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final EventReminder reminder = new EventReminder(
            new AppEventProperties(WARSAW, new AppEventProperties.EventSettings(new AppEventProperties.Reminder(8)))
    );

    @Test
    void reminderForOrdinaryDayIsEightAmDayBefore() {
        ZonedDateTime result = reminder.reminderFor(eventOn(LocalDate.of(2026, 9, 15)));

        assertThat(result.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 14, 8, 0));
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void reminderForFirstOfMonthCrossesMonthBoundary() {
        ZonedDateTime result = reminder.reminderFor(eventOn(LocalDate.of(2026, 9, 1)));

        assertThat(result.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 8, 31, 8, 0));
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void reminderForFirstOfYearCrossesYearBoundary() {
        ZonedDateTime result = reminder.reminderFor(eventOn(LocalDate.of(2027, 1, 1)));

        assertThat(result.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 31, 8, 0));
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    void reminderAcrossSpringForwardStaysAtZoneEight() {
        // Spring-forward in Warsaw 2026: Sunday 2026-03-29 at 02:00 → 03:00.
        // Event on 2026-03-30 → reminder lands on the transition day itself, after the jump.
        ZonedDateTime result = reminder.reminderFor(eventOn(LocalDate.of(2026, 3, 30)));

        assertThat(result.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 3, 29, 8, 0));
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void reminderAcrossFallBackStaysAtZoneEight() {
        // Fall-back in Warsaw 2026: Sunday 2026-10-25 at 03:00 → 02:00.
        // Event on 2026-10-26 → reminder lands on the transition day itself, after the fall-back.
        ZonedDateTime result = reminder.reminderFor(eventOn(LocalDate.of(2026, 10, 26)));

        assertThat(result.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 25, 8, 0));
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    private Event eventOn(LocalDate date) {
        AppUser user = new AppUser("reminder-test@example.com", "x");
        return new Event(user, date, null, "t", null, null);
    }
}
