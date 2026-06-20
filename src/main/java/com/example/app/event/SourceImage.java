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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "source_image",
        indexes = { @Index(name = "ix_source_image_user_resolved", columnList = "user_id,resolved_at") }
)
public class SourceImage {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;

    @Column(name = "mime_type", nullable = false, length = 32)
    private String mimeType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "last_error_kind", length = 32)
    private String lastErrorKind;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    protected SourceImage() {
    }

    public SourceImage(AppUser user, byte[] data, String mimeType) {
        this.user = Objects.requireNonNull(user, "user");
        this.data = Objects.requireNonNull(data, "data");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
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

    public byte[] getData() {
        return data;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getLastErrorKind() {
        return lastErrorKind;
    }

    public void setLastErrorKind(String lastErrorKind) {
        this.lastErrorKind = lastErrorKind;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
