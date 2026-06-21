package com.example.app.event;

import com.example.app.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "app_event",
        indexes = { @Index(name = "ix_app_event_user_date", columnList = "user_id,event_date") }
)
public class Event {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
    }

    public Event(AppUser user, LocalDate eventDate, LocalTime eventTime, String title, String requirements, String notes) {
        this.user = Objects.requireNonNull(user, "user");
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

    public AppUser getUser() {
        return user;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = Objects.requireNonNull(eventDate, "eventDate");
    }

    public void setEventTime(LocalTime eventTime) {
        this.eventTime = eventTime;
    }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
