package com.example.app.event;

import com.example.app.user.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    // `nulls last` is JPQL; both Postgres and H2 honor it. Spring Data derived
    // method names can't express null-handling, so the query stays explicit.
    @Query("""
            select e from Event e
            where e.user = :user
              and e.eventDate >= :today
            order by e.eventDate asc, e.eventTime asc nulls last
            """)
    List<Event> findUpcomingByUser(@Param("user") AppUser user, @Param("today") LocalDate today);
}
