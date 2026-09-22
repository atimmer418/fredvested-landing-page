package com.fredvested.web.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.ZoneId;

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

    // Calculator inputs captured at signup; null when out of range or from a
    // pre-calculator client. `interacted` false = untouched slider defaults.
    @Column(name = "current_age")
    private Integer currentAge;

    @Column(name = "invest_monthly")
    private Integer investMonthly;

    @Column(name = "retire_monthly")
    private Integer retireMonthly;

    @Column(name = "interacted")
    private Boolean interacted;

    // Annual return scenario the projection used (8 / 10 / 12 today); null when
    // out of range or from a client that predates the scenario selector.
    @Column(name = "return_assumption_pct")
    private Integer returnAssumptionPct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @Column(name = "ip_hash")
    private String ipHash;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("America/New_York"));
    }

    public enum WaitlistStatus {
        WAITLISTFOUNDER, WAITLISTNORMAL, INVITED, CLAIMED, DECLINED
    }
}
