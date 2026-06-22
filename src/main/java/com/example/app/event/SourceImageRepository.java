package com.example.app.event;

import com.example.app.user.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SourceImageRepository extends JpaRepository<SourceImage, UUID> {

    Optional<SourceImage> findByIdAndUser(UUID id, AppUser user);

    @Modifying
    @Query("""
            DELETE FROM SourceImage si
             WHERE NOT EXISTS (
                   SELECT 1 FROM ProposedEvent pe
                    WHERE pe.sourceImage = si
                      AND pe.status = com.example.app.event.ProposedEvent.ProposedEventStatus.PENDING
               )
               AND si.lastErrorKind IS NULL
               AND si.resolvedAt IS NOT NULL
            """)
    int purgeEligible();
}
