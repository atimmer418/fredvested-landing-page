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

    // Double opt-in. Only the SHA-256 of the confirmation token is stored, and it is
    // cleared on use, so an already-used token is indistinguishable from an unknown one.
    @Column(name = "confirmation_token_hash", length = 64)
    private String confirmationTokenHash;

    @Column(name = "confirmation_sent_at")
    private LocalDateTime confirmationSentAt;

    @Column(name = "confirmation_expires_at")
    private LocalDateTime confirmationExpiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    // Denormalised latest delivery status of this address's most recent email
    @Column(name = "email_status", length = 20)
    private String emailStatus;

    // Set once, never cleared. The outbox publisher refuses to send to a suppressed
    // address; a bounce, a complaint or an unsubscribe all land here.
    @Column(name = "suppressed_at")
    private LocalDateTime suppressedAt;

    @Column(name = "suppression_reason", length = 40)
    private String suppressionReason;

    public static final String SUPPRESSION_HARD_BOUNCE = "hard_bounce";
    public static final String SUPPRESSION_COMPLAINT = "complaint";
    public static final String SUPPRESSION_MANUAL = "manual";
    public static final String SUPPRESSION_UNSUBSCRIBE = "unsubscribe";

    public boolean isSuppressed() {
        return suppressedAt != null;
    }

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
