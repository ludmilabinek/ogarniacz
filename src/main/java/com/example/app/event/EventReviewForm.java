package com.example.app.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EventReviewForm {

    public enum Action {
        ACCEPT,
        REJECT
    }

    private List<ProposedEventDecision> decisions = new ArrayList<>();

    public List<ProposedEventDecision> getDecisions() {
        return decisions;
    }

    public void setDecisions(List<ProposedEventDecision> decisions) {
        this.decisions = decisions;
    }

    public static class ProposedEventDecision {

        private UUID proposedEventId;

        private Action action = Action.ACCEPT;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate eventDate;

        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        private LocalTime eventTime;

        @NotBlank
        @Size(max = 200)
        private String title;

        @Size(max = 2000)
        private String requirements;

        @Size(max = 2000)
        private String notes;

        // Populated by the GET handler so the template can hide non-PENDING rows on
        // re-render. Not user-submitted; the POST handler ignores any value here and
        // reads the authoritative status from the database.
        private String status;

        public UUID getProposedEventId() {
            return proposedEventId;
        }

        public void setProposedEventId(UUID proposedEventId) {
            this.proposedEventId = proposedEventId;
        }

        public Action getAction() {
            return action;
        }

        public void setAction(Action action) {
            this.action = action;
        }

        public LocalDate getEventDate() {
            return eventDate;
        }

        public void setEventDate(LocalDate eventDate) {
            this.eventDate = eventDate;
        }

        public LocalTime getEventTime() {
            return eventTime;
        }

        public void setEventTime(LocalTime eventTime) {
            this.eventTime = eventTime;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getRequirements() {
            return requirements;
        }

        public void setRequirements(String requirements) {
            this.requirements = requirements;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
