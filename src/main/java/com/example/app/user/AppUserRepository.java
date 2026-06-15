package com.example.app.user;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<AppUser> findByIcalToken(String icalToken);

    // Pessimistic-write lock used by IcalSubscriptionService.getOrCreateToken to
    // serialize concurrent first-visits on the same user row. Without this, two
    // GETs to /settings against an unminted user row could both read null,
    // both mint, both UPDATE — the second commit silently overwrites the first.
    // The UNIQUE constraint on ical_token does not catch this (the two new
    // tokens differ).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<AppUser> findAndLockById(UUID id);
}
