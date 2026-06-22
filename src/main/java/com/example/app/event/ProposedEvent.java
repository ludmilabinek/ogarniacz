package com.example.app.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "proposed_event",
        indexes = {
                @Index(name = "ix_proposed_event_source_image", columnList = "source_image_id"),
                @Index(name = "ix_proposed_event_source_image_status", columnList = "source_image_id, status")
        }
)
public class ProposedEvent {

    public enum ProposedEventStatus {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_image_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SourceImage sourceImage;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "event_time")
    private LocalTime eventTime;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String requirements;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProposedEventStatus status = ProposedEventStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ProposedEvent() {
    }

    public ProposedEvent(SourceImage sourceImage,
                         LocalDate eventDate,
                         LocalTime eventTime,
                         String title,
                         String requirements,
                         String notes) {
        this.sourceImage = Objects.requireNonNull(sourceImage, "sourceImage");
        this.eventDate = Objects.requireNonNull(eventDate, "eventDate");
        this.eventTime = eventTime;
        this.title = Objects.requireNonNull(title, "title");
        this.requirements = requirements;
        this.notes = notes;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public SourceImage getSourceImage() {
        return sourceImage;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public LocalTime getEventTime() {
        return eventTime;
    }

    public String getTitle() {
        return title;
    }

    public String getRequirements() {
        return requirements;
    }

    public String getNotes() {
        return notes;
    }

    public ProposedEventStatus getStatus() {
        return status;
    }

    public void setStatus(ProposedEventStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
