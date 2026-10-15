package com.fredvested.web.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** One webhook delivery from Resend. svix_id is unique: a redelivery is a no-op. */
@Entity
@Table(name = "email_event")
@Data
public class EmailEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "svix_id", nullable = false, unique = true, length = 64)
    private String svixId;

    @Column(name = "resend_email_id", length = 64)
    private String resendEmailId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    // The event's own created_at from the payload, used for ordering
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String payload;
}
