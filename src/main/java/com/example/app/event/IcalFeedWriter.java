package com.example.app.event;

import com.example.app.user.AppUser;

import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Name;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RefreshInterval;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.model.property.immutable.ImmutableAction;
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale;
import net.fortuna.ical4j.model.property.immutable.ImmutableStatus;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

@Component
public class IcalFeedWriter {

    private static final String UID_HOST = "ogarniacz.fly.dev";
    private static final String PRODID_VALUE = "-//Ogarniacz//Ogarniacz Feed 1.0//EN";
    private static final String CAL_NAME = "Ogarniacz";
    private static final String REFRESH_TTL = "PT6H";
    private static final Duration REFRESH_DURATION = Duration.ofHours(6);

    private final EventReminder eventReminder;
    private final AppEventProperties properties;

    public IcalFeedWriter(EventReminder eventReminder, AppEventProperties properties) {
        this.eventReminder = eventReminder;
        this.properties = properties;
    }

    public String write(AppUser user, List<Event> events) {
        Calendar calendar = new Calendar();
        calendar.add(new ProdId(PRODID_VALUE));
        calendar.add(ImmutableVersion.VERSION_2_0);
        calendar.add(ImmutableCalScale.GREGORIAN);
        calendar.add(new Name(CAL_NAME));
        calendar.add(new XProperty("X-WR-CALNAME", CAL_NAME));
        calendar.add(new XProperty("X-PUBLISHED-TTL", REFRESH_TTL));
        calendar.add(new RefreshInterval(new ParameterList(), REFRESH_DURATION));

        // RFC 5545 §3.6.5: any TZID referenced from a DTSTART must be defined
        // by a VTIMEZONE in the same calendar. Emit only when needed so the
        // empty-feed and date-only-feed cases stay minimal.
        if (events.stream().anyMatch(e -> e.getEventTime() != null)) {
            TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
            calendar.add(registry.getTimeZone(properties.timezone().getId()).getVTimeZone());
        }

        for (Event event : events) {
            calendar.add(buildEvent(event));
        }

        return outputAsString(calendar);
    }

    private VEvent buildEvent(Event event) {
        VEvent vevent;
        if (event.getEventTime() == null) {
            LocalDate start = event.getEventDate();
            LocalDate end = start.plusDays(1);
            vevent = new VEvent(start, end, event.getTitle());
        } else {
            ZonedDateTime start = ZonedDateTime.of(
                    event.getEventDate(), event.getEventTime(), properties.timezone());
            vevent = new VEvent(start, event.getTitle());
        }

        vevent.add(new Uid(event.getId() + "@" + UID_HOST));
        vevent.add(new Sequence(0));
        vevent.add(ImmutableStatus.VEVENT_CONFIRMED);

        String description = mergedDescription(event);
        if (description != null) {
            vevent.add(new Description(description));
        }
        vevent.add(buildAlarm(event));

        return vevent;
    }

    private VAlarm buildAlarm(Event event) {
        Instant triggerAt = eventReminder.reminderFor(event).toInstant();
        VAlarm alarm = new VAlarm(triggerAt);
        alarm.add(ImmutableAction.DISPLAY);
        alarm.add(new Description("Ogarniacz reminder"));
        return alarm;
    }

    private static String mergedDescription(Event event) {
        boolean hasReq = event.getRequirements() != null && !event.getRequirements().isBlank();
        boolean hasNotes = event.getNotes() != null && !event.getNotes().isBlank();
        if (hasReq && hasNotes) {
            return event.getRequirements() + "\n\n" + event.getNotes();
        }
        if (hasReq) {
            return event.getRequirements();
        }
        if (hasNotes) {
            return event.getNotes();
        }
        return null;
    }

    private static String outputAsString(Calendar calendar) {
        try (StringWriter sw = new StringWriter()) {
            new CalendarOutputter(false).output(calendar, sw);
            return sw.toString();
        } catch (IOException e) {
            throw new IllegalStateException("ical4j outputter failed", e);
        }
    }
}
