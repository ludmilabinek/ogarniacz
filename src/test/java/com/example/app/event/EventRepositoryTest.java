package com.example.app.event;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class EventRepositoryTest {

    @Autowired
    EventRepository eventRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    DataSource dataSource;

    @Test
    void appEventTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, null, "APP_EVENT", null)) {
            assertThat(rs.next()).as("app_event table should exist").isTrue();
        }
    }

    @Test
    void findUpcomingByUserExcludesOtherUsersEvents() {
        AppUser alice = appUserRepository.save(new AppUser("alice-evt@example.com", "$2a$10$placeholderHashForTestPurpose."));
        AppUser bob = appUserRepository.save(new AppUser("bob-evt@example.com", "$2a$10$placeholderHashForTestPurpose."));

        LocalDate future = LocalDate.now().plusDays(3);
        entityManager.persist(new Event(alice, future, null, "alice-event", null, null));
        entityManager.persist(new Event(bob, future, null, "bob-event", null, null));
        entityManager.flush();

        List<Event> aliceUpcoming = eventRepository.findUpcomingByUser(alice, LocalDate.now());

        assertThat(aliceUpcoming).extracting(Event::getTitle).containsExactly("alice-event");
        assertThat(aliceUpcoming).extracting(Event::getTitle).doesNotContain("bob-event");
    }

    @Test
    void findUpcomingByUserExcludesPastEvents() {
        AppUser alice = appUserRepository.save(new AppUser("alice-past@example.com", "$2a$10$placeholderHashForTestPurpose."));

        entityManager.persist(new Event(alice, LocalDate.now().minusDays(1), null, "yesterday-event", null, null));
        entityManager.flush();

        List<Event> upcoming = eventRepository.findUpcomingByUser(alice, LocalDate.now());

        assertThat(upcoming).isEmpty();
    }

    @Test
    void findUpcomingByUserOrdersByDateAscThenTimeAscNullsLast() {
        AppUser alice = appUserRepository.save(new AppUser("alice-order@example.com", "$2a$10$placeholderHashForTestPurpose."));

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        // Persist in scrambled order to prove ORDER BY does the sorting (not insertion order).
        entityManager.persist(new Event(alice, tomorrow, null, "tomorrow-untimed", null, null));
        entityManager.persist(new Event(alice, today, LocalTime.of(14, 0), "today-1400", null, null));
        entityManager.persist(new Event(alice, today, LocalTime.of(9, 0), "today-0900", null, null));
        entityManager.flush();

        List<Event> upcoming = eventRepository.findUpcomingByUser(alice, today);

        assertThat(upcoming).extracting(Event::getTitle)
                .containsExactly("today-0900", "today-1400", "tomorrow-untimed");
    }
}
