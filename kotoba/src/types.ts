/**
 * analytics kotoba — public analytics-catalog record types.
 *
 * Per ADR-2606011400 (Consensys pattern) — MIXED split:
 *   - PRODUCT (etzhayyim front, this package): the public catalog of dashboards +
 *     AGGREGATE metric datapoints + reports (classification=public AT records).
 *   - INFRA (stays etzhayyim, NOT here): raw per-user behavioral-event ingestion +
 *     warehouse aggregation (the "funnel analysis" engine, pod-side). PII-derived
 *     raw events are consumed via consent-capability and never enter these public
 *     records.
 *
 * CUSTODY CONTRACT (ADR-2605172400): a metric datapoint is an AGGREGATE
 * (name + integer value + coarse dimensions). It MUST NOT carry user / session /
 * device identifiers — AT records are public by design and PII is prohibited.
 *
 * AT-Lexicon: no float. Metric values are integers (counts / sums / basis points);
 * express rates as basis-points integers, not floats.
 *
 * Identity hierarchy:
 *   did:web:analytics.etzhayyim.com                          — controller
 *   did:web:analytics.etzhayyim.com:dashboard:{dashboardId}  — a dashboard
 *   did:web:analytics.etzhayyim.com:metric:{metricId}        — a metric datapoint
 *   did:web:analytics.etzhayyim.com:report:{reportId}        — a report
 */

export const ANALYTICS_DID_PREFIX = "did:web:analytics.etzhayyim.com:" as const;

export const DASHBOARD_COLLECTION = "com.etzhayyim.apps.analytics.dashboard";
export const METRIC_COLLECTION = "com.etzhayyim.apps.analytics.event";
export const REPORT_COLLECTION = "com.etzhayyim.apps.analytics.report";

// ─── Dashboard ──────────────────────────────────────────────────────

export interface Widget {
  metricName: string;
  /** Visualization kind, e.g. "line" / "bar" / "counter". */
  vizType: string;
}

export interface DashboardRecord {
  did: string;
  dashboardId: string;
  name: string;
  description?: string;
  widgets: Widget[];
  createdAt: string;
}
export interface DashboardView extends DashboardRecord {
  dashboardUri: string;
}
export interface CreateDashboardInput {
  dashboardId: string;
  name: string;
  description?: string;
  widgets?: Widget[];
}
export interface CreateDashboardOutput {
  status: "created" | "alreadyExists" | "rejected";
  dashboardUri?: string;
  did?: string;
  dashboardId?: string;
  error?: string;
}
export interface GetDashboardInput {
  dashboardId: string;
}
export interface GetDashboardOutput {
  dashboard?: DashboardView;
  error?: string;
}
export interface ListDashboardsInput {
  limit?: number;
  cursor?: string;
}
export interface ListDashboardsOutput {
  items: DashboardView[];
  cursor?: string;
  total: number;
}

// ─── Metric datapoint (aggregate event) ─────────────────────────────

export interface Dimension {
  key: string;
  value: string;
}

export interface MetricRecord {
  did: string;
  metricId: string;
  metricName: string;
  /** Aggregate value (count / sum / basis points). Integer. */
  value: number;
  /** Coarse dimensions ONLY (e.g. channel=web). No user/session/device IDs. */
  dimensions: Dimension[];
  occurredAt: string;
  createdAt: string;
}
export interface MetricView extends MetricRecord {
  metricUri: string;
}
export interface RecordMetricInput {
  metricId: string;
  metricName: string;
  value: number;
  dimensions?: Dimension[];
  occurredAt: string;
}
export interface RecordMetricOutput {
  status: "recorded" | "alreadyExists" | "rejected";
  metricUri?: string;
  did?: string;
  metricId?: string;
  error?: string;
}
export interface ListMetricsInput {
  metricName?: string;
  since?: string;
  limit?: number;
  cursor?: string;
}
export interface ListMetricsOutput {
  items: MetricView[];
  cursor?: string;
  total: number;
}
export interface GetMetricsInput {
  metricName: string;
  since?: string;
  maxScan?: number;
}
export interface GetMetricsOutput {
  metricName?: string;
  count?: number;
  sum?: number;
  min?: number;
  max?: number;
  truncated?: boolean;
  error?: string;
}

// ─── Report ─────────────────────────────────────────────────────────

export type ReportStatus = "draft" | "published";

export interface ReportRecord {
  did: string;
  reportId: string;
  title: string;
  reportType: string;
  /** Optional dashboard this report summarizes (FK). */
  dashboardId?: string;
  summary?: string;
  status: ReportStatus;
  createdAt: string;
}
export interface ReportView extends ReportRecord {
  reportUri: string;
}
export interface CreateReportInput {
  reportId: string;
  title: string;
  reportType: string;
  dashboardId?: string;
  summary?: string;
}
export interface CreateReportOutput {
  status: "created" | "alreadyExists" | "rejected" | "dashboardNotFound";
  reportUri?: string;
  did?: string;
  reportId?: string;
  error?: string;
}
export interface PublishReportInput {
  reportId: string;
}
export interface PublishReportOutput {
  status: "published" | "notFound" | "rejected";
  reportId?: string;
  newStatus?: ReportStatus;
  error?: string;
}
export interface ListReportsInput {
  reportType?: string;
  status?: ReportStatus;
  dashboardId?: string;
  limit?: number;
  cursor?: string;
}
export interface ListReportsOutput {
  items: ReportView[];
  cursor?: string;
  total: number;
}

// ─── Coverage ───────────────────────────────────────────────────────

export interface CoverageInput {
  maxScan?: number;
}
export interface CoverageOutput {
  dashboardCount?: number;
  metricCount?: number;
  reportCount?: number;
  reportsByStatus?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Helpers ────────────────────────────────────────────────────────

export function isInt(n: unknown): n is number {
  return typeof n === "number" && Number.isInteger(n);
}

export function dashboardDidFor(id: string): string {
  return `${ANALYTICS_DID_PREFIX}dashboard:${id.toLowerCase()}`;
}
export function dashboardRkey(id: string): string {
  return `dashboard-${id.toLowerCase()}`;
}
export function metricDidFor(id: string): string {
  return `${ANALYTICS_DID_PREFIX}metric:${id.toLowerCase()}`;
}
export function metricRkey(id: string): string {
  return `metric-${id.toLowerCase()}`;
}
export function reportDidFor(id: string): string {
  return `${ANALYTICS_DID_PREFIX}report:${id.toLowerCase()}`;
}
export function reportRkey(id: string): string {
  return `report-${id.toLowerCase()}`;
}
