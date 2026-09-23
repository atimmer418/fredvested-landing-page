package com.fredvested.web.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
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

    // Server-recomputed projection from the inputs above (freedom_age holds the
    // recomputed age); null when the inputs are incomplete or the target is unreachable.
    @Column(name = "computed_freedom_date")
    private LocalDate computedFreedomDate;

    @Column(name = "computed_portfolio_target")
    private Long computedPortfolioTarget;

    @Column(name = "revealed_before_submit", nullable = false)
    private Boolean revealedBeforeSubmit = false;

    // Attribution, sanitised server-side (AttributionSanitizer). Last touch = the
    // visit they converted on; first_* = the content that originally found them.
    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_medium", length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 100)
    private String utmCampaign;

    @Column(name = "utm_content", length = 100)
    private String utmContent;

    @Column(name = "utm_term", length = 100)
    private String utmTerm;

    @Column(name = "first_utm_source", length = 100)
    private String firstUtmSource;

    @Column(name = "first_utm_campaign", length = 100)
    private String firstUtmCampaign;

    @Column(name = "first_utm_content", length = 100)
    private String firstUtmContent;

    @Column(name = "first_touch_at")
    private LocalDateTime firstTouchAt;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Column(name = "landing_path", length = 255)
    private String landingPath;

    @Column(name = "device_type", length = 20)
    private String deviceType;

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
