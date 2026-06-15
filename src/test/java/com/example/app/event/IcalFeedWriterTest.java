package com.example.app.event;

import com.example.app.user.AppUser;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Name;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RefreshInterval;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.property.XProperty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IcalFeedWriterTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final AppEventProperties PROPS = new AppEventProperties(
            WARSAW,
            new AppEventProperties.EventSettings(new AppEventProperties.Reminder(8))
    );

    private final EventReminder reminder = new EventReminder(PROPS);
    private final IcalFeedWriter writer = new IcalFeedWriter(reminder, PROPS);

    private static final AppUser USER = new AppUser("test@example.com", "x");

    @Test
    void emptyEventsReturnsEnvelopeOnly() throws Exception {
        Calendar parsed = parse(writer.write(USER, List.of()));

        assertThat(parsed.getComponents(Component.VEVENT)).isEmpty();
        assertThat(parsed.<Version>getRequiredProperty(Property.VERSION).getValue()).isEqualTo("2.0");
        assertThat(parsed.<ProdId>getRequiredProperty(Property.PRODID).getValue())
                .isEqualTo("-//Ogarniacz//Ogarniacz Feed 1.0//EN");
        assertThat(parsed.<Name>getRequiredProperty("NAME").getValue()).isEqualTo("Ogarniacz");
        assertThat(parsed.<XProperty>getRequiredProperty("X-WR-CALNAME").getValue()).isEqualTo("Ogarniacz");
        assertThat(parsed.<XProperty>getRequiredProperty("X-PUBLISHED-TTL").getValue()).isEqualTo("PT6H");
        assertThat(parsed.<RefreshInterval>getRequiredProperty("REFRESH-INTERVAL").getValue()).isEqualTo("PT6H");
    }

    @Test
    void dateOnlyEventEmitsDtstartValueDateAndExplicitDtend() throws Exception {
        Event event = newEvent(LocalDate.of(2026, 6, 20), null, "field trip", null, null);
        VEvent vevent = singleEvent(writer.write(USER, List.of(event)));

        DtStart<?> dtstart = vevent.getRequiredProperty(Property.DTSTART);
        assertThat(dtstart.getValue()).isEqualTo("20260620");
        assertThat(dtstart.getParameter(Parameter.VALUE)).isPresent()
                .map(p -> p.getValue()).contains("DATE");
        assertThat(dtstart.getParameter(Parameter.TZID)).isEmpty();

        DtEnd<?> dtend = vevent.getRequiredProperty(Property.DTEND);
        assertThat(dtend.getValue()).isEqualTo("20260621");
        assertThat(dtend.getParameter(Parameter.VALUE)).isPresent()
                .map(p -> p.getValue()).contains("DATE");
    }

    @Test
    void timedEventEmitsDtstartWithTzidEuropeWarsaw() throws Exception {
        Event event = newEvent(LocalDate.of(2026, 6, 20), LocalTime.of(14, 30), "school play", null, null);
        VEvent vevent = singleEvent(writer.write(USER, List.of(event)));

        DtStart<?> dtstart = vevent.getRequiredProperty(Property.DTSTART);
        assertThat(dtstart.getValue()).isEqualTo("20260620T143000");
        assertThat(dtstart.getParameter(Parameter.TZID)).isPresent()
                .map(p -> p.getValue()).contains("Europe/Warsaw");
        assertThat(vevent.getProperty(Property.DTEND)).isEmpty();
    }

    @Test
    void uidIsEventIdAtOgarniaczFlyDevAndStableAcrossWrites() throws Exception {
        Event event = newEvent(LocalDate.of(2026, 6, 20), null, "pasowanie", null, null);

        String firstUid = singleEvent(writer.write(USER, List.of(event)))
                .<Uid>getRequiredProperty(Property.UID).getValue();
        String secondUid = singleEvent(writer.write(USER, List.of(event)))
                .<Uid>getRequiredProperty(Property.UID).getValue();

        assertThat(firstUid).isEqualTo(secondUid);
        assertThat(firstUid).matches("^[0-9a-f-]{36}@ogarniacz\\.fly\\.dev$");
        assertThat(firstUid).isEqualTo(event.getId() + "@ogarniacz.fly.dev");
    }

    @Test
    void valarmTriggerOnRegularDayIsMorningOfDayBeforeAt08Warsaw() throws Exception {
        // 2026-06-15 is a Monday in mid-summer, well clear of any DST transition.
        // Morning-of-day-before at 08:00 Warsaw (CEST/UTC+02:00) → 06:00 UTC on 2026-06-14.
        Event event = newEvent(LocalDate.of(2026, 6, 15), null, "warm-up day", null, null);

        Instant trigger = parseTrigger(writer.write(USER, List.of(event)));

        assertThat(trigger).isEqualTo(Instant.parse("2026-06-14T06:00:00Z"));
    }

    @Test
    void valarmTriggerSurvivesSpringForward() throws Exception {
        // Warsaw spring-forward: 2026-03-29 at 02:00 → 03:00 (CET → CEST).
        // Event on 2026-03-30 → reminder fires 2026-03-29 at 08:00 Warsaw local,
        // which is already in CEST (UTC+02:00) → 06:00 UTC.
        Event event = newEvent(LocalDate.of(2026, 3, 30), null, "post-DST trip", null, null);

        Instant trigger = parseTrigger(writer.write(USER, List.of(event)));

        assertThat(trigger).isEqualTo(Instant.parse("2026-03-29T06:00:00Z"));
    }

    @Test
    void valarmTriggerSurvivesFallBack() throws Exception {
        // Warsaw fall-back: 2026-10-25 at 03:00 → 02:00 (CEST → CET).
        // Event on 2026-10-26 → reminder fires 2026-10-25 at 08:00 Warsaw local,
        // which is already in CET (UTC+01:00) → 07:00 UTC.
        Event event = newEvent(LocalDate.of(2026, 10, 26), null, "post-DST trip", null, null);

        Instant trigger = parseTrigger(writer.write(USER, List.of(event)));

        assertThat(trigger).isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
    }

    @Test
    void summaryAndDescriptionHandlePolishDiacriticsAndNewlines() throws Exception {
        String title = "Pasowanie — przynieś czapkę";
        String requirements = "ą,ć,ę;ł\\ś";
        Event event = newEvent(LocalDate.of(2026, 6, 20), null, title, requirements, null);

        VEvent vevent = singleEvent(writer.write(USER, List.of(event)));

        assertThat(vevent.<Summary>getRequiredProperty(Property.SUMMARY).getValue()).isEqualTo(title);
        assertThat(vevent.<Description>getRequiredProperty(Property.DESCRIPTION).getValue()).isEqualTo(requirements);
    }

    @Test
    void descriptionOmittedWhenRequirementsAndNotesBothNull() throws Exception {
        Event event = newEvent(LocalDate.of(2026, 6, 20), null, "no-detail event", null, null);
        VEvent vevent = singleEvent(writer.write(USER, List.of(event)));

        assertThat(vevent.getProperty(Property.DESCRIPTION)).isEmpty();
    }

    @Test
    void descriptionJoinsRequirementsAndNotesWithBlankLine() throws Exception {
        Event event = newEvent(LocalDate.of(2026, 6, 20), null, "x",
                "yellow shirt", "pickup at 12:00");
        VEvent vevent = singleEvent(writer.write(USER, List.of(event)));

        assertThat(vevent.<Description>getRequiredProperty(Property.DESCRIPTION).getValue())
                .isEqualTo("yellow shirt\n\npickup at 12:00");
    }

    @Test
    void sequenceIsZeroOnFreshRender() throws Exception {
        Event event = newEvent(LocalDate.of(2026, 6, 20), null, "x", null, null);
        VEvent vevent = singleEvent(writer.write(USER, List.of(event)));

        assertThat(vevent.<Sequence>getRequiredProperty(Property.SEQUENCE).getValue()).isEqualTo("0");
    }

    @Test
    void statusIsConfirmedOnEveryEvent() throws Exception {
        Event a = newEvent(LocalDate.of(2026, 6, 20), null, "a", null, null);
        Event b = newEvent(LocalDate.of(2026, 6, 21), LocalTime.of(9, 0), "b", null, null);

        Calendar parsed = parse(writer.write(USER, List.of(a, b)));
        List<CalendarComponent> vevents = parsed.getComponents(Component.VEVENT);

        assertThat(vevents).hasSize(2);
        for (CalendarComponent c : vevents) {
            assertThat(((VEvent) c).<Status>getRequiredProperty(Property.STATUS).getValue())
                    .isEqualTo("CONFIRMED");
        }
    }

    // --- helpers ----------------------------------------------------------

    private static Event newEvent(LocalDate date, LocalTime time, String title,
                                  String requirements, String notes) {
        Event event = new Event(USER, date, time, title, requirements, notes);
        // In production the @GeneratedValue UUID is set by Hibernate on save.
        // This test runs without a JPA context, so we set it via reflection
        // (mirroring what JPA would set) — the writer needs a non-null id to
        // build the UID, and several tests assert UID stability across writes
        // of the same Event.
        setField(event, "id", UUID.randomUUID());
        return event;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to set " + name, e);
        }
    }

    private static Calendar parse(String ics) throws IOException, ParserException {
        return new CalendarBuilder().build(new StringReader(ics));
    }

    private static VEvent singleEvent(String ics) throws IOException, ParserException {
        Calendar parsed = parse(ics);
        List<CalendarComponent> events = parsed.getComponents(Component.VEVENT);
        assertThat(events).hasSize(1);
        return (VEvent) events.get(0);
    }

    private static Instant parseTrigger(String ics) throws IOException, ParserException {
        VEvent vevent = singleEvent(ics);
        VAlarm alarm = vevent.getAlarms().get(0);
        Trigger trigger = alarm.getRequiredProperty(Property.TRIGGER);
        return trigger.getDate();
    }
}
