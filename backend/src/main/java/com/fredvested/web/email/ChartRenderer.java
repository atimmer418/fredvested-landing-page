package com.fredvested.web.email;

/**
 * The outbox publisher's seam for the growth chart: GrowthChartRenderer::render in
 * production, a fake PNG in tests, so nothing in the sending path depends on Java2D.
 */
@FunctionalInterface
public interface ChartRenderer {
    GrowthChartRenderer.Png render(Projection projection);
}
