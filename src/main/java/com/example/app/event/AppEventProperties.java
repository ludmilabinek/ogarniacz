package com.example.app.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties(prefix = "app")
public record AppEventProperties(ZoneId timezone, EventSettings event) {

    public AppEventProperties {
        if (timezone == null) {
            timezone = ZoneId.of("Europe/Warsaw");
        }
        if (event == null) {
            event = new EventSettings(new Reminder(8));
        }
    }

    public record EventSettings(Reminder reminder) {
        public EventSettings {
            if (reminder == null) {
                reminder = new Reminder(8);
            }
        }
    }

    public record Reminder(int hour) {
    }
}
