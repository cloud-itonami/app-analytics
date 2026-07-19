/**
 * analytics kotoba — barrel.
 *
 * Per ADR-2606011400 (Consensys pattern) MIXED split. The public analytics
 * catalog on the etzhayyim substrate (AT PDS records; no RW).
 *
 *   dashboard : createDashboard / getDashboard / listDashboards
 *   metric    : recordMetric (aggregate, no PII) / listMetrics / getMetrics (rollup)
 *   report    : createReport (optional FK→dashboard) / publishReport / listReports
 *   coverage
 *
 * Raw per-user behavioral ingestion + warehouse aggregation stays etzhayyim infra.
 */

export * from "./types.js";
export {
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
} from "./registry.js";
