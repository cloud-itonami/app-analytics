import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  createDashboard,
  getDashboard,
  listDashboards,
  recordMetric,
  listMetrics,
  getMetrics,
  createReport,
  publishReport,
  listReports,
  coverage,
} from "../src/index.js";

describe("analytics kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:analytics.etzhayyim.com" });
  });

  describe("dashboard", () => {
    it("creates, reads, lists, idempotent, validates widgets", async () => {
      expect((await createDashboard(e, { dashboardId: "D-1", name: "Growth", widgets: [{ metricName: "signups", vizType: "line" }] })).status).toBe("created");
      expect((await getDashboard(e, { dashboardId: "D-1" })).dashboard?.widgets.length).toBe(1);
      expect((await createDashboard(e, { dashboardId: "D-1", name: "dup" })).status).toBe("alreadyExists");
      expect((await createDashboard(e, { dashboardId: "D-2", name: "x", widgets: [{ metricName: "", vizType: "bar" }] as any })).status).toBe("rejected");
      expect((await listDashboards(e)).total).toBe(1);
    });
  });

  describe("aggregate metrics + rollup", () => {
    it("records integer aggregates, rejects float, rolls up sum/count/min/max", async () => {
      expect((await recordMetric(e, { metricId: "M-1", metricName: "signups", value: 10, occurredAt: "2026-06-01T00:00:00Z", dimensions: [{ key: "channel", value: "web" }] })).status).toBe("recorded");
      expect((await recordMetric(e, { metricId: "M-2", metricName: "signups", value: 25, occurredAt: "2026-06-02T00:00:00Z" })).status).toBe("recorded");
      expect((await recordMetric(e, { metricId: "M-3", metricName: "signups", value: 1.5, occurredAt: "2026-06-03T00:00:00Z" })).status).toBe("rejected"); // float
      expect((await listMetrics(e, { metricName: "signups", since: "2026-06-02T00:00:00Z" })).total).toBe(1);
      const roll = await getMetrics(e, { metricName: "signups" });
      expect(roll.count).toBe(2);
      expect(roll.sum).toBe(35);
      expect(roll.min).toBe(10);
      expect(roll.max).toBe(25);
    });
  });

  describe("report + coverage", () => {
    beforeEach(async () => {
      await createDashboard(e, { dashboardId: "D-1", name: "Growth" });
    });
    it("creates report (optional FK→dashboard), rejects bad dashboard, publishes", async () => {
      expect((await createReport(e, { reportId: "R-1", title: "Weekly", reportType: "weekly", dashboardId: "D-1" })).status).toBe("created");
      expect((await createReport(e, { reportId: "R-2", title: "Ad-hoc", reportType: "adhoc" })).status).toBe("created");
      expect((await createReport(e, { reportId: "R-X", title: "x", reportType: "y", dashboardId: "GHOST" })).status).toBe("dashboardNotFound");
      expect((await publishReport(e, { reportId: "R-1" })).newStatus).toBe("published");
      expect((await publishReport(e, { reportId: "R-1" })).status).toBe("rejected");
      expect((await listReports(e, { status: "published" })).total).toBe(1);
    });
    it("coverage rolls up the three collections", async () => {
      await recordMetric(e, { metricId: "M-1", metricName: "signups", value: 10, occurredAt: "2026-06-01T00:00:00Z" });
      await createReport(e, { reportId: "R-1", title: "Weekly", reportType: "weekly" });
      const cov = await coverage(e);
      expect(cov.dashboardCount).toBe(1);
      expect(cov.metricCount).toBe(1);
      expect(cov.reportCount).toBe(1);
      expect(cov.reportsByStatus?.draft).toBe(1);
    });
  });
});
