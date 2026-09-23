package com.fredvested.web.repository;

import com.fredvested.web.model.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    boolean existsByEmail(String email);

    WaitlistEntry findByEmail(String email);

    // Indexed lookup by the SHA-256 of a confirmation token (never by the raw token)
    Optional<WaitlistEntry> findByConfirmationTokenHash(String confirmationTokenHash);

    // Targeted writes for the outbox publisher. It runs across a network call, so it
    // must never merge a stale snapshot of the row back over a concurrent
    // confirmation, unsubscribe or bounce; it only ever touches its own columns.
    @Modifying
    @Transactional
    @Query("update WaitlistEntry w set w.confirmationTokenHash = :hash, w.confirmationExpiresAt = :expires where w.id = :id")
    int setConfirmationToken(@Param("id") Long id, @Param("hash") String hash, @Param("expires") LocalDateTime expires);

    @Modifying
    @Transactional
    @Query("update WaitlistEntry w set w.emailStatus = :status where w.id = :id")
    int setEmailStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Transactional
    @Query("update WaitlistEntry w set w.emailStatus = :status, w.confirmationSentAt = :sentAt where w.id = :id")
    int markConfirmationSent(@Param("id") Long id, @Param("status") String status, @Param("sentAt") LocalDateTime sentAt);

    long countByStatus(WaitlistEntry.WaitlistStatus status);

    // Rows behind the public head-start stat. The about page disclosure states the assumptions
    // behind every averaged row, so only rows it accurately describes qualify: a calculator
    // result the user actually adjusted (interacted), made with one of the current return
    // scenarios (mirrors RETURN_* in index.html; older clients used different math and
    // tampered payloads fall outside the set), and a freedom age inside the calculator's
    // own bounds (18 to 100). Every head-start query below shares this one filter.
    String HEAD_START_ROWS = " FROM WaitlistEntry w"
        + " WHERE w.freedomAge BETWEEN 18 AND 100"
        + " AND w.returnAssumptionPct IN (8, 10, 12)"
        + " AND w.interacted = true";

    @Query("SELECT AVG(w.freedomAge)" + HEAD_START_ROWS)
    Double getAverageFreedomAge();

    // Sample size behind getAverageFreedomAge().
    @Query("SELECT COUNT(w)" + HEAD_START_ROWS)
    long countHeadStartProjections();

    // Measurement period behind getAverageFreedomAge(), disclosed alongside the stat.
    @Query("SELECT MIN(w.createdAt)" + HEAD_START_ROWS)
    LocalDateTime getFirstProjectionAt();

    @Query("SELECT MAX(w.createdAt)" + HEAD_START_ROWS)
    LocalDateTime getLastProjectionAt();
}
