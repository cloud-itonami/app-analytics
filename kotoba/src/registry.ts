/**
 * analytics kotoba — dashboard + aggregate-metric + report registries + metric
 * rollup + coverage. AT PDS records (no RW). Reports may FK-reference a dashboard.
 * Metric datapoints are aggregates only (no per-user PII).
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  DASHBOARD_COLLECTION,
  METRIC_COLLECTION,
  REPORT_COLLECTION,
  dashboardDidFor,
  dashboardRkey,
  isInt,
  metricDidFor,
  metricRkey,
  reportDidFor,
  reportRkey,
  type CoverageInput,
  type CoverageOutput,
  type CreateDashboardInput,
  type CreateDashboardOutput,
  type CreateReportInput,
  type CreateReportOutput,
  type DashboardRecord,
  type DashboardView,
  type GetDashboardInput,
  type GetDashboardOutput,
  type GetMetricsInput,
  type GetMetricsOutput,
  type ListDashboardsInput,
  type ListDashboardsOutput,
  type ListMetricsInput,
  type ListMetricsOutput,
  type ListReportsInput,
  type ListReportsOutput,
  type MetricRecord,
  type MetricView,
  type PublishReportInput,
  type PublishReportOutput,
  type RecordMetricInput,
  type RecordMetricOutput,
  type ReportRecord,
  type ReportView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

async function exists(e: Etzhayyim, collection: string, rkey: string): Promise<boolean> {
  const resp = await e.read({ collection, rkey }).catch(() => ({ records: [] }));
  return Boolean(resp.records[0]?.value);
}

// ─── Dashboard ──────────────────────────────────────────────────────

export async function createDashboard(e: Etzhayyim, input: CreateDashboardInput): Promise<CreateDashboardOutput> {
  if (!input.dashboardId || !input.name) return { status: "rejected", error: "missingRequiredFields" };
  for (const w of input.widgets ?? []) {
    if (!w || !w.metricName || !w.vizType) return { status: "rejected", error: "invalidWidget" };
  }
  const rkey = dashboardRkey(input.dashboardId);
  const existing = await e.read<DashboardRecord>({ collection: DASHBOARD_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", dashboardUri: existing.records[0].uri, did: existing.records[0].value.did, dashboardId: input.dashboardId };
  }
  const did = dashboardDidFor(input.dashboardId);
  const record: DashboardRecord = {
    did,
    dashboardId: input.dashboardId,
    name: input.name,
    description: input.description,
    widgets: (input.widgets ?? []).map((w) => ({ metricName: w.metricName, vizType: w.vizType })),
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: DASHBOARD_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "created", dashboardUri: receipt.uri, did, dashboardId: input.dashboardId };
}

export async function getDashboard(e: Etzhayyim, input: GetDashboardInput): Promise<GetDashboardOutput> {
  if (!input.dashboardId) return { error: "invalidDashboardId" };
  const resp = await e.read<DashboardRecord>({ collection: DASHBOARD_COLLECTION, rkey: dashboardRkey(input.dashboardId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { dashboard: { ...r.value, dashboardUri: r.uri } };
}

export async function listDashboards(e: Etzhayyim, input: ListDashboardsInput = {}): Promise<ListDashboardsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<DashboardRecord>({ collection: DASHBOARD_COLLECTION, cursor: input.cursor, limit });
  const items: DashboardView[] = resp.records.map((r) => ({ ...r.value, dashboardUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Metric datapoint ───────────────────────────────────────────────

export async function recordMetric(e: Etzhayyim, input: RecordMetricInput): Promise<RecordMetricOutput> {
  if (!input.metricId || !input.metricName || !input.occurredAt) return { status: "rejected", error: "missingRequiredFields" };
  if (!isInt(input.value)) return { status: "rejected", error: "valueMustBeInteger" };
  for (const d of input.dimensions ?? []) {
    if (!d || !d.key || d.value == null) return { status: "rejected", error: "invalidDimension" };
  }
  const rkey = metricRkey(input.metricId);
  const existing = await e.read<MetricRecord>({ collection: METRIC_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", metricUri: existing.records[0].uri, did: existing.records[0].value.did, metricId: input.metricId };
  }
  const did = metricDidFor(input.metricId);
  const record: MetricRecord = {
    did,
    metricId: input.metricId,
    metricName: input.metricName,
    value: input.value,
    dimensions: (input.dimensions ?? []).map((d) => ({ key: d.key, value: d.value })),
    occurredAt: input.occurredAt,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: METRIC_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "recorded", metricUri: receipt.uri, did, metricId: input.metricId };
}

export async function listMetrics(e: Etzhayyim, input: ListMetricsInput = {}): Promise<ListMetricsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<MetricRecord>({ collection: METRIC_COLLECTION, cursor: input.cursor, limit });
  const items: MetricView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.metricName && v.metricName !== input.metricName) return false;
      if (input.since && v.occurredAt < input.since) return false;
      return true;
    })
    .map((r) => ({ ...r.value, metricUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

/** App-layer aggregate rollup over a metric (AT PDS has no GROUP BY). */
export async function getMetrics(e: Etzhayyim, input: GetMetricsInput): Promise<GetMetricsOutput> {
  if (!input.metricName) return { error: "invalidMetricName" };
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  let cursor: string | undefined;
  let scanned = 0;
  let count = 0;
  let sum = 0;
  let min: number | undefined;
  let max: number | undefined;
  while (scanned < maxScan) {
    const page = await e.read<MetricRecord>({ collection: METRIC_COLLECTION, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      scanned += 1;
      const v = r.value;
      if (v.metricName !== input.metricName) continue;
      if (input.since && v.occurredAt < input.since) continue;
      count += 1;
      sum += v.value;
      min = min === undefined ? v.value : Math.min(min, v.value);
      max = max === undefined ? v.value : Math.max(max, v.value);
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return { metricName: input.metricName, count, sum, min, max, truncated: scanned >= maxScan };
}

// ─── Report ─────────────────────────────────────────────────────────

export async function createReport(e: Etzhayyim, input: CreateReportInput): Promise<CreateReportOutput> {
  if (!input.reportId || !input.title || !input.reportType) return { status: "rejected", error: "missingRequiredFields" };
  if (input.dashboardId && !(await exists(e, DASHBOARD_COLLECTION, dashboardRkey(input.dashboardId)))) {
    return { status: "dashboardNotFound", error: `dashboardNotFound:${input.dashboardId}` };
  }
  const rkey = reportRkey(input.reportId);
  const existing = await e.read<ReportRecord>({ collection: REPORT_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", reportUri: existing.records[0].uri, did: existing.records[0].value.did, reportId: input.reportId };
  }
  const did = reportDidFor(input.reportId);
  const record: ReportRecord = {
    did,
    reportId: input.reportId,
    title: input.title,
    reportType: input.reportType,
    dashboardId: input.dashboardId,
    summary: input.summary,
    status: "draft",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: REPORT_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "created", reportUri: receipt.uri, did, reportId: input.reportId };
}

export async function publishReport(e: Etzhayyim, input: PublishReportInput): Promise<PublishReportOutput> {
  if (!input.reportId) return { status: "rejected", error: "invalidReportId" };
  const rkey = reportRkey(input.reportId);
  const resp = await e.read<ReportRecord>({ collection: REPORT_COLLECTION, rkey }).catch(() => ({ records: [] }));
  const rep = resp.records[0]?.value;
  if (!rep) return { status: "notFound", error: "reportNotFound" };
  if (rep.status === "published") return { status: "rejected", error: "alreadyPublished" };
  await e.write({ collection: REPORT_COLLECTION, record: { ...rep, status: "published" } as unknown as Record<string, unknown>, rkey });
  return { status: "published", reportId: input.reportId, newStatus: "published" };
}

export async function listReports(e: Etzhayyim, input: ListReportsInput = {}): Promise<ListReportsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<ReportRecord>({ collection: REPORT_COLLECTION, cursor: input.cursor, limit });
  const items: ReportView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.reportType && v.reportType !== input.reportType) return false;
      if (input.status && v.status !== input.status) return false;
      if (input.dashboardId && v.dashboardId !== input.dashboardId) return false;
      return true;
    })
    .map((r) => ({ ...r.value, reportUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

async function countAll<T>(e: Etzhayyim, collection: string, maxScan: number, onRow: (v: T) => void): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const dashboardCount = await countAll<DashboardRecord>(e, DASHBOARD_COLLECTION, maxScan, () => {});
  const metricCount = await countAll<MetricRecord>(e, METRIC_COLLECTION, maxScan, () => {});
  const reportsByStatus: Record<string, number> = {};
  const reportCount = await countAll<ReportRecord>(e, REPORT_COLLECTION, maxScan, (v) => {
    reportsByStatus[v.status] = (reportsByStatus[v.status] ?? 0) + 1;
  });
  return {
    dashboardCount,
    metricCount,
    reportCount,
    reportsByStatus,
    truncated: dashboardCount >= maxScan || metricCount >= maxScan || reportCount >= maxScan,
  };
}
