package com.fredvested.web.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "waitlist_signups")
@Data
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "freedom_age")
    private Integer freedomAge;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @Column(name = "ip_hash")
    private String ipHash;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum WaitlistStatus {
        WAITLISTFOUNDER, WAITLISTNORMAL, INVITED, CLAIMED, DECLINED
    }
}
