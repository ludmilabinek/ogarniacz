package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Controller
public class CalendarController {

    private static final MediaType TEXT_CALENDAR_UTF8 =
            new MediaType("text", "calendar", StandardCharsets.UTF_8);

    private final AppUserRepository appUserRepository;
    private final EventRepository eventRepository;
    private final IcalFeedWriter icalFeedWriter;
    private final Clock clock;

    public CalendarController(AppUserRepository appUserRepository,
                              EventRepository eventRepository,
                              IcalFeedWriter icalFeedWriter,
                              Clock clock) {
        this.appUserRepository = appUserRepository;
        this.eventRepository = eventRepository;
        this.icalFeedWriter = icalFeedWriter;
        this.clock = clock;
    }

    @GetMapping("/calendar/{token}.ics")
    public ResponseEntity<String> feed(@PathVariable("token") String token) {
        // 404 (not 401) when no user matches: hides whether the token namespace
        // endpoint exists. Same response shape as Spring's default 404 for an
        // unmatched route.
        AppUser user = appUserRepository.findByIcalToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<Event> events = eventRepository.findUpcomingByUser(user, LocalDate.now(clock));
        String body = icalFeedWriter.write(user, events);

        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR_UTF8)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, max-age=0")
                .header(HttpHeaders.VARY, "Accept")
                .body(body);
    }
}
