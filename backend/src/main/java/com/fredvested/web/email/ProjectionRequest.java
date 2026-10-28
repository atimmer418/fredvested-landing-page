package com.fredvested.web.email;

import lombok.Data;

import java.util.List;

/**
 * The raw `projection` object the landing page sends with a signup, exactly as
 * JSON: every number boxed (null = absent) so the validator, not Jackson, decides
 * what is missing. ProjectionValidator turns it into a Projection or rejects the
 * whole signup; nothing here is ever stored or rendered as is.
 */
@Data
public class ProjectionRequest {
    private Double currentAge;
    private Double freedomAge;
    private Integer freedomMonth;
    private Integer freedomYear;
    private Double monthlyInvest;
    private Double targetIncome;
    private Double existingBalance;
    private Integer returnRate;
    private String scenario;
    private Integer stakeYears;
    private String stakeSource;
    private Double totalContributed;
    private Double portfolioTarget;
    private List<PointRequest> series;
    private List<PointRequest> contributions;

    @Data
    public static class PointRequest {
        private Double age;
        private Double value;
    }
}
