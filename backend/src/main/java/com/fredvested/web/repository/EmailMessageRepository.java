package com.fredvested.web.repository;

import com.fredvested.web.model.EmailMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, Long> {

    /**
     * Rows the publisher should try now: pending and due (next_attempt_at unset or
     * past), plus rows stuck in "sending" since before {@code stale} (a publisher
     * that died mid-send). The due filter lives in the query so rows in backoff
     * never crowd newer rows out of the window.
     */
    @Query("select m from EmailMessage m where (m.status = 'pending' and (m.nextAttemptAt is null or m.nextAttemptAt <= :now))"
            + " or (m.status = 'sending' and m.statusUpdatedAt <= :stale) order by m.queuedAt asc")
    List<EmailMessage> findDue(@Param("now") LocalDateTime now, @Param("stale") LocalDateTime stale, Pageable page);

    /** Atomic claim: only one publisher can move a row into "sending". Returns 0 when someone else got it. */
    @Modifying
    @Transactional
    @Query("update EmailMessage m set m.status = 'sending', m.attempts = m.attempts + 1, m.statusUpdatedAt = :now"
            + " where m.id = :id and (m.status = 'pending' or (m.status = 'sending' and m.statusUpdatedAt <= :stale))")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("stale") LocalDateTime stale);

    // Locked read: webhook events for one message serialise on the row, so the
    // out-of-order guard is a real compare-and-set, not a read-then-write race.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from EmailMessage m where m.resendEmailId = :resendEmailId")
    Optional<EmailMessage> findByResendEmailIdForUpdate(@Param("resendEmailId") String resendEmailId);

    Optional<EmailMessage> findByResendEmailId(String resendEmailId);

    Optional<EmailMessage> findByUnsubscribeTokenHash(String unsubscribeTokenHash);

    List<EmailMessage> findByWaitlistIdAndTemplateAndStatus(Long waitlistId, String template, String status);
}
