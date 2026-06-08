package com.example.app.event;

import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

@Component
public class EventReminder {

    private final AppEventProperties properties;

    public EventReminder(AppEventProperties properties) {
        this.properties = properties;
    }

    public ZonedDateTime reminderFor(Event event) {
        return event.getEventDate()
                .minusDays(1)
                .atTime(properties.event().reminder().hour(), 0)
                .atZone(properties.timezone());
    }
}
