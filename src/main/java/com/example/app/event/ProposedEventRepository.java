package com.example.app.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProposedEventRepository extends JpaRepository<ProposedEvent, UUID> {

    // Mirror EventRepository.findUpcomingByUser: derived method names can't express
    // `nulls last`, so the query stays explicit. Postgres + H2 both honor it.
    @Query("""
            select p from ProposedEvent p
            where p.sourceImage = :sourceImage
            order by p.eventDate asc, p.eventTime asc nulls last
            """)
    List<ProposedEvent> findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(
            @Param("sourceImage") SourceImage sourceImage);
}
