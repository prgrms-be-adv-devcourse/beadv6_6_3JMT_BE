import { createHash } from "node:crypto";
import {
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "..");
const outputDirectory = resolve(repositoryRoot, "k8s/addons/elk/dashboards");

const APP_DATA_VIEW = "application-logs";
const GATEWAY_DATA_VIEW = "gateway-access";
const GATEWAY_EVENT_QUERY = 'gateway.eventType: "GATEWAY_ACCESS"';
const STARTUP_ERROR_QUERY =
  'level: "ERROR" and (thread_name: "main" or logger_name: ("org.springframework.boot.SpringApplication" or *Flyway*))';

const kql = (expression = "") => ({
  expression,
  language: "kql",
});

const dataView = (refId) => ({
  type: "data_view_reference",
  ref_id: refId,
});

const staticColor = (color) => ({
  type: "static",
  color,
});

function stableId(dashboardId, panelName) {
  const hex = createHash("sha256")
    .update(`${dashboardId}:${panelName}`)
    .digest("hex");

  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(
    13,
    16,
  )}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function panel(dashboardId, name, grid, config) {
  return {
    grid,
    config,
    id: stableId(dashboardId, name),
    type: "vis",
  };
}

function metricPanel({
  dashboardId,
  name,
  title,
  dataViewId,
  grid,
  query = "",
  filter = "",
  operation = "count",
  field,
  percentile,
  color,
  subtitle,
}) {
  const metric = {
    type: "primary",
    operation,
    filter: kql(filter),
    label: title,
  };

  if (operation === "count" || operation === "unique_count") {
    metric.empty_as_null = false;
  }
  if (field) {
    metric.field = field;
  }
  if (percentile !== undefined) {
    metric.percentile = percentile;
  }
  if (color) {
    metric.color = staticColor(color);
  }
  if (subtitle) {
    metric.subtitle = subtitle;
  }

  return panel(dashboardId, name, grid, {
    title,
    description: "",
    hide_title: false,
    type: "metric",
    data_source: dataView(dataViewId),
    sampling: 1,
    ignore_global_filters: false,
    query: kql(query),
    metrics: [metric],
    styling: {
      primary: {
        position: "bottom",
        labels: {
          alignment: "center",
        },
        value: {
          sizing: "auto",
          alignment: "center",
        },
      },
    },
  });
}

function dateHistogram() {
  return {
    operation: "date_histogram",
    field: "@timestamp",
    suggested_interval: "auto",
    use_original_time_range: false,
    include_empty_rows: true,
    drop_partial_intervals: false,
  };
}

function xyAxis(yTitle) {
  return {
    x: {
      title: {
        visible: true,
      },
      ticks: {
        visible: true,
      },
      grid: {
        visible: true,
      },
      domain: {
        type: "fit",
        rounding: false,
      },
      labels: {
        orientation: "horizontal",
      },
    },
    y: {
      title: {
        text: yTitle,
        visible: true,
      },
      scale: "linear",
      ticks: {
        visible: true,
      },
      grid: {
        visible: true,
      },
      domain: {
        type: "full",
        rounding: true,
      },
      labels: {
        orientation: "horizontal",
      },
    },
  };
}

function barStyling() {
  return {
    overlays: {
      partial_buckets: {
        visible: false,
      },
      current_time_marker: {
        visible: false,
      },
    },
    bars: {
      minimum_height: 1,
      data_labels: {
        visible: false,
      },
    },
  };
}

function lineStyling() {
  return {
    overlays: {
      partial_buckets: {
        visible: false,
      },
      current_time_marker: {
        visible: false,
      },
    },
    fitting: {
      type: "none",
    },
    interpolation: "smooth",
    points: {
      visibility: "auto",
    },
  };
}

function legend(position = "top") {
  return {
    position,
    placement: "outside",
    layout: {
      type: "grid",
      truncate: {
        enabled: false,
        max_lines: 1,
      },
    },
    visibility: "visible",
  };
}

function termsBreakdown(field, label, limit = 10) {
  return {
    operation: "terms",
    fields: [field],
    label,
    limit,
    other_bucket: {
      include_documents_without_field: false,
    },
    rank_by: {
      type: "metric",
      metric_index: 0,
      direction: "desc",
    },
    aggregate_first: true,
    color: {
      mode: "categorical",
      palette: "default",
      mapping: [],
    },
  };
}

function xyTermsPanel({
  dashboardId,
  name,
  title,
  description,
  dataViewId,
  grid,
  field,
  fieldLabel,
  query = "",
  yLabel,
  limit = 10,
}) {
  return panel(dashboardId, name, grid, {
    title,
    description,
    hide_title: false,
    type: "xy",
    query: kql(query),
    layers: [
      {
        type: "bar_stacked",
        data_source: dataView(dataViewId),
        sampling: 1,
        ignore_global_filters: false,
        x: dateHistogram(),
        y: [
          {
            operation: "count",
            empty_as_null: true,
            label: yLabel,
            filter: kql(""),
          },
        ],
        breakdown_by: termsBreakdown(field, fieldLabel, limit),
      },
    ],
    axis: xyAxis(yLabel),
    styling: barStyling(),
    legend: legend(),
    filters: [],
  });
}

function xyFiltersPanel({
  dashboardId,
  name,
  title,
  description,
  dataViewId,
  grid,
  query = "",
  filters,
  breakdownLabel,
  yLabel,
}) {
  return panel(dashboardId, name, grid, {
    title,
    description,
    hide_title: false,
    type: "xy",
    query: kql(query),
    layers: [
      {
        type: "bar_stacked",
        data_source: dataView(dataViewId),
        sampling: 1,
        ignore_global_filters: false,
        x: dateHistogram(),
        y: [
          {
            operation: "count",
            empty_as_null: true,
            label: yLabel,
            filter: kql(""),
          },
        ],
        breakdown_by: {
          operation: "filters",
          label: breakdownLabel,
          filters: filters.map(({ expression, label }) => ({
            filter: kql(expression),
            label,
          })),
          aggregate_first: true,
          color: {
            mode: "categorical",
            palette: "LEGACY_PALETTE_status",
            mapping: [],
          },
        },
      },
    ],
    axis: xyAxis(yLabel),
    styling: barStyling(),
    legend: legend(),
    filters: [],
  });
}

function percentilePanel({
  dashboardId,
  name,
  title,
  description,
  dataViewId,
  grid,
  field,
  query = "",
}) {
  return panel(dashboardId, name, grid, {
    title,
    description,
    hide_title: false,
    type: "xy",
    query: kql(query),
    layers: [
      {
        type: "line",
        data_source: dataView(dataViewId),
        sampling: 1,
        ignore_global_filters: false,
        x: dateHistogram(),
        y: [
          {
            operation: "percentile",
            field,
            percentile: 99,
            label: "p99",
            filter: kql(""),
            color: staticColor("#d36086"),
          },
          {
            operation: "percentile",
            field,
            percentile: 95,
            label: "p95",
            filter: kql(""),
            color: staticColor("#9170b8"),
          },
          {
            operation: "percentile",
            field,
            percentile: 50,
            label: "p50",
            filter: kql(""),
            color: staticColor("#6092c0"),
          },
        ],
      },
    ],
    axis: xyAxis("Response time, ms"),
    styling: lineStyling(),
    legend: legend("right"),
    filters: [],
  });
}

function tableMetric({
  operation,
  label,
  field,
  percentile,
}) {
  const metric = {
    operation,
    label,
    filter: kql(""),
  };

  if (operation === "count") {
    metric.empty_as_null = true;
  }
  if (field) {
    metric.field = field;
  }
  if (percentile !== undefined) {
    metric.percentile = percentile;
  }

  return metric;
}

function tableRow(field, label, limit, rankByMetric = true) {
  return {
    operation: "terms",
    fields: [field],
    label,
    limit,
    other_bucket: {
      include_documents_without_field: false,
    },
    rank_by: rankByMetric
      ? {
          type: "metric",
          metric_index: 0,
          direction: "desc",
        }
      : {
          type: "alphabetical",
          direction: "asc",
        },
  };
}

function dataTablePanel({
  dashboardId,
  name,
  title,
  description,
  dataViewId,
  grid,
  query = "",
  metric,
  rows,
}) {
  return panel(dashboardId, name, grid, {
    title,
    description,
    hide_title: false,
    type: "data_table",
    data_source: dataView(dataViewId),
    sampling: 1,
    ignore_global_filters: false,
    query: kql(query),
    metrics: [metric],
    rows,
    filters: [],
  });
}

function timeSlider(dashboardId) {
  return {
    id: stableId(dashboardId, "time-range"),
    grow: true,
    width: "large",
    config: {
      is_anchored: false,
      start_percentage_of_time_range: 0,
      end_percentage_of_time_range: 1,
    },
    type: "time_slider_control",
  };
}

function optionsListControl({
  dashboardId,
  name,
  dataViewId,
  title,
  field,
  width = "medium",
}) {
  return {
    id: stableId(dashboardId, name),
    grow: true,
    width,
    config: {
      use_global_filters: true,
      ignore_validations: false,
      data_view_id: dataViewId,
      title,
      field_name: field,
      exclude: false,
      sort: {
        by: "_count",
        direction: "desc",
      },
      exists_selected: false,
      run_past_timeout: false,
      search_technique: "prefix",
      selected_options: [],
      single_select: false,
    },
    type: "options_list_control",
  };
}

function dashboard(title, description, panels, pinnedPanels) {
  return {
    title,
    description,
    options: {
      auto_apply_filters: true,
      hide_panel_borders: false,
      hide_panel_titles: false,
      sync_colors: true,
      sync_cursor: true,
      sync_tooltips: true,
      use_margins: true,
    },
    tags: [],
    time_range: {
      from: "now-24h",
      to: "now",
    },
    filters: [],
    query: kql(""),
    panels,
    pinned_panels: pinnedPanels,
  };
}

const serviceHealthId = "prompthub-service-health";
const serviceHealthPanels = [
  metricPanel({
    dashboardId: serviceHealthId,
    name: "total-requests",
    title: "Total requests",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    color: "#6092c0",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "2xx-responses",
    title: "2xx responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 8, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 200 and gateway.status < 300",
    color: "#209280",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "4xx-responses",
    title: "4xx responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 16, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 400 and gateway.status < 500",
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "5xx-responses",
    title: "5xx responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 500 and gateway.status < 600",
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "p95-latency",
    title: "p95 latency",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 32, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    operation: "percentile",
    field: "gateway.durationMs",
    percentile: 95,
    color: "#9170b8",
    subtitle: "milliseconds",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "application-errors",
    title: "ERROR logs",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 40, y: 0, w: 8, h: 6 },
    filter: 'level: "ERROR"',
    color: "#cc5642",
  }),
  xyTermsPanel({
    dashboardId: serviceHealthId,
    name: "request-volume-by-route",
    title: "Request volume by Gateway route",
    description: "Gateway requests over time, split by route ID.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 6, w: 24, h: 12 },
    field: "gateway.routeId.keyword",
    fieldLabel: "Gateway route",
    query: GATEWAY_EVENT_QUERY,
    yLabel: "Requests",
  }),
  xyFiltersPanel({
    dashboardId: serviceHealthId,
    name: "responses-by-status-class",
    title: "HTTP responses by status class",
    description: "Gateway responses over time, split into successful and error classes.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 6, w: 24, h: 12 },
    query: GATEWAY_EVENT_QUERY,
    breakdownLabel: "Status class",
    yLabel: "Responses",
    filters: [
      {
        expression: "gateway.status >= 200 and gateway.status < 300",
        label: "2xx",
      },
      {
        expression: "gateway.status >= 400 and gateway.status < 500",
        label: "4xx",
      },
      {
        expression: "gateway.status >= 500 and gateway.status < 600",
        label: "5xx",
      },
    ],
  }),
  percentilePanel({
    dashboardId: serviceHealthId,
    name: "latency-percentiles",
    title: "Gateway response time percentiles",
    description: "p50, p95, and p99 response time across Gateway requests.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 18, w: 48, h: 12 },
    field: "gateway.durationMs",
    query: GATEWAY_EVENT_QUERY,
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "slowest-routes",
    title: "Top 10 slowest Gateway routes",
    description: "Routes ranked by p95 response time.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 30, w: 24, h: 14 },
    query: GATEWAY_EVENT_QUERY,
    metric: tableMetric({
      operation: "percentile",
      field: "gateway.durationMs",
      percentile: 95,
      label: "p95, ms",
    }),
    rows: [
      tableRow("gateway.routeId.keyword", "Gateway route", 10),
      tableRow("gateway.method.keyword", "Method", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "server-error-paths",
    title: "Top 10 paths returning 5xx",
    description: "Gateway paths ranked by server-error response count.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 30, w: 24, h: 14 },
    query: `${GATEWAY_EVENT_QUERY} and gateway.status >= 500 and gateway.status < 600`,
    metric: tableMetric({
      operation: "count",
      label: "5xx responses",
    }),
    rows: [
      tableRow("gateway.path.keyword", "Path", 10),
      tableRow("gateway.routeId.keyword", "Gateway route", 1, false),
      tableRow("gateway.method.keyword", "Method", 1, false),
    ],
  }),
  xyTermsPanel({
    dashboardId: serviceHealthId,
    name: "warning-error-by-service",
    title: "WARN and ERROR logs by service",
    description: "Potential incidents over time, split by application service.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 44, w: 48, h: 12 },
    field: "service.name",
    fieldLabel: "Application service",
    query: 'level: ("WARN" or "ERROR")',
    yLabel: "WARN / ERROR logs",
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "error-loggers",
    title: "Top ERROR loggers",
    description: "Loggers ranked by ERROR occurrence count.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 56, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR logs",
    }),
    rows: [
      tableRow("logger_name", "Logger", 10),
      tableRow("service.name", "Application service", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "error-pods",
    title: "Top Pods with ERROR logs",
    description: "Kubernetes Pods ranked by ERROR occurrence count.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 56, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR logs",
    }),
    rows: [
      tableRow("kubernetes.pod_name", "Pod", 10),
      tableRow("service.name", "Application service", 1, false),
      tableRow("logger_name", "Logger", 1, false),
    ],
  }),
];

const gatewayAnomaliesId = "prompthub-gateway-anomalies";
const gatewayAnomaliesPanels = [
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "401-responses",
    title: "401 responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status: 401",
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "403-responses",
    title: "403 responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 8, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status: 403",
    color: "#da8b45",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "404-responses",
    title: "404 responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 16, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status: 404",
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "5xx-responses",
    title: "5xx responses",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 500 and gateway.status < 600",
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "unknown-route",
    title: "UNKNOWN route",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 32, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: 'gateway.routeId: "UNKNOWN"',
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "slow-requests",
    title: "Requests ≥ 1000ms",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 40, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.durationMs >= 1000",
    color: "#9170b8",
  }),
  xyFiltersPanel({
    dashboardId: gatewayAnomaliesId,
    name: "error-status-trend",
    title: "Gateway error responses by status",
    description: "Authentication, routing, and upstream errors over time.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 6, w: 24, h: 12 },
    query: GATEWAY_EVENT_QUERY,
    breakdownLabel: "HTTP status",
    yLabel: "Responses",
    filters: [
      { expression: "gateway.status: 401", label: "401" },
      { expression: "gateway.status: 403", label: "403" },
      { expression: "gateway.status: 404", label: "404" },
      {
        expression: "gateway.status >= 500 and gateway.status < 600",
        label: "5xx",
      },
    ],
  }),
  xyFiltersPanel({
    dashboardId: gatewayAnomaliesId,
    name: "unknown-route-trend",
    title: "Unknown route and 404 trend",
    description: "Unmatched routes compared with all 404 responses.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 6, w: 24, h: 12 },
    query: GATEWAY_EVENT_QUERY,
    breakdownLabel: "Routing anomaly",
    yLabel: "Responses",
    filters: [
      { expression: 'gateway.routeId: "UNKNOWN"', label: "UNKNOWN route" },
      { expression: "gateway.status: 404", label: "404" },
    ],
  }),
  xyTermsPanel({
    dashboardId: gatewayAnomaliesId,
    name: "slow-request-trend",
    title: "Slow requests by route",
    description: "Requests taking at least one second, split by route.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 18, w: 24, h: 12 },
    field: "gateway.routeId.keyword",
    fieldLabel: "Gateway route",
    query: `${GATEWAY_EVENT_QUERY} and gateway.durationMs >= 1000`,
    yLabel: "Slow requests",
  }),
  percentilePanel({
    dashboardId: gatewayAnomaliesId,
    name: "latency-percentiles",
    title: "Gateway latency percentiles",
    description: "p50, p95, and p99 response time for anomaly correlation.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 18, w: 24, h: 12 },
    field: "gateway.durationMs",
    query: GATEWAY_EVENT_QUERY,
  }),
  dataTablePanel({
    dashboardId: gatewayAnomaliesId,
    name: "unknown-and-404-paths",
    title: "Top unknown and 404 paths",
    description: "Paths most often associated with routing misses.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 30, w: 24, h: 14 },
    query: `${GATEWAY_EVENT_QUERY} and (gateway.routeId: "UNKNOWN" or gateway.status: 404)`,
    metric: tableMetric({
      operation: "count",
      label: "Responses",
    }),
    rows: [
      tableRow("gateway.path.keyword", "Path", 10),
      tableRow("gateway.status", "Status", 3, false),
      tableRow("gateway.method.keyword", "Method", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: gatewayAnomaliesId,
    name: "p95-by-route",
    title: "Routes with highest p95 latency",
    description: "Gateway routes ranked by p95 response time.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 30, w: 24, h: 14 },
    query: GATEWAY_EVENT_QUERY,
    metric: tableMetric({
      operation: "percentile",
      field: "gateway.durationMs",
      percentile: 95,
      label: "p95, ms",
    }),
    rows: [
      tableRow("gateway.routeId.keyword", "Gateway route", 10),
      tableRow("gateway.method.keyword", "Method", 1, false),
    ],
  }),
  xyTermsPanel({
    dashboardId: gatewayAnomaliesId,
    name: "authenticated-requests",
    title: "Requests by authentication state",
    description: "Gateway requests split by authenticated state.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 44, w: 24, h: 12 },
    field: "gateway.authenticated",
    fieldLabel: "Authenticated",
    query: GATEWAY_EVENT_QUERY,
    yLabel: "Requests",
    limit: 2,
  }),
  xyTermsPanel({
    dashboardId: gatewayAnomaliesId,
    name: "requests-by-method",
    title: "Requests by HTTP method",
    description: "Gateway requests split by HTTP method.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 44, w: 24, h: 12 },
    field: "gateway.method.keyword",
    fieldLabel: "HTTP method",
    query: GATEWAY_EVENT_QUERY,
    yLabel: "Requests",
    limit: 10,
  }),
];

const runtimeIncidentsId = "prompthub-runtime-incidents";
const runtimeIncidentsPanels = [
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "warning-logs",
    title: "WARN logs",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 0, w: 8, h: 6 },
    filter: 'level: "WARN"',
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "error-logs",
    title: "ERROR logs",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 8, y: 0, w: 8, h: 6 },
    filter: 'level: "ERROR"',
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "affected-services",
    title: "Affected services",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 16, y: 0, w: 8, h: 6 },
    filter: 'level: ("WARN" or "ERROR")',
    operation: "unique_count",
    field: "service.name",
    color: "#6092c0",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "affected-pods",
    title: "Affected Pods",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 0, w: 8, h: 6 },
    filter: 'level: ("WARN" or "ERROR")',
    operation: "unique_count",
    field: "kubernetes.pod_name",
    color: "#6092c0",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "stack-trace-errors",
    title: "ERROR with stack trace",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 32, y: 0, w: 8, h: 6 },
    filter: 'level: "ERROR" and stack_trace: *',
    color: "#9170b8",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "startup-errors",
    title: "Startup ERROR",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 40, y: 0, w: 8, h: 6 },
    filter: STARTUP_ERROR_QUERY,
    color: "#cc5642",
  }),
  xyTermsPanel({
    dashboardId: runtimeIncidentsId,
    name: "logs-by-level",
    title: "Application logs by level",
    description: "Structured application logs over time, split by level.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 6, w: 24, h: 12 },
    field: "level",
    fieldLabel: "Log level",
    yLabel: "Log events",
    limit: 6,
  }),
  xyTermsPanel({
    dashboardId: runtimeIncidentsId,
    name: "warning-error-by-service",
    title: "WARN and ERROR logs by service",
    description: "Potential incidents over time, split by application service.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 6, w: 24, h: 12 },
    field: "service.name",
    fieldLabel: "Application service",
    query: 'level: ("WARN" or "ERROR")',
    yLabel: "WARN / ERROR logs",
  }),
  xyTermsPanel({
    dashboardId: runtimeIncidentsId,
    name: "errors-by-pod",
    title: "ERROR logs by Kubernetes Pod",
    description: "ERROR logs over time, split by Pod to identify isolated failures.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 18, w: 48, h: 12 },
    field: "kubernetes.pod_name",
    fieldLabel: "Pod",
    query: 'level: "ERROR"',
    yLabel: "ERROR logs",
  }),
  dataTablePanel({
    dashboardId: runtimeIncidentsId,
    name: "error-loggers",
    title: "Top ERROR loggers",
    description: "Loggers ranked by ERROR occurrence count.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 30, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR logs",
    }),
    rows: [
      tableRow("logger_name", "Logger", 10),
      tableRow("service.name", "Application service", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: runtimeIncidentsId,
    name: "error-service-pods",
    title: "Services and Pods with ERROR logs",
    description: "Affected services and Pods ranked by ERROR occurrence count.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 30, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR logs",
    }),
    rows: [
      tableRow("service.name", "Application service", 10),
      tableRow("kubernetes.pod_name", "Pod", 3, false),
      tableRow("logger_name", "Logger", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: runtimeIncidentsId,
    name: "startup-error-loggers",
    title: "Startup ERROR loggers",
    description: "Main-thread and Spring startup loggers ranked by ERROR count.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 44, w: 48, h: 14 },
    query: STARTUP_ERROR_QUERY,
    metric: tableMetric({
      operation: "count",
      label: "Startup ERROR logs",
    }),
    rows: [
      tableRow("logger_name", "Logger", 10),
      tableRow("service.name", "Application service", 5, false),
      tableRow("kubernetes.pod_name", "Pod", 3, false),
      tableRow("thread_name", "Thread", 1, false),
    ],
  }),
];

const dashboards = {
  [serviceHealthId]: dashboard(
    "[PromptHub] Service Health",
    "Shared operations view of PromptHub request volume, HTTP status, Gateway latency, and application WARN/ERROR signals.",
    serviceHealthPanels,
    [
      timeSlider(serviceHealthId),
      optionsListControl({
        dashboardId: serviceHealthId,
        name: "application-service",
        dataViewId: APP_DATA_VIEW,
        title: "Application service",
        field: "service.name",
      }),
      optionsListControl({
        dashboardId: serviceHealthId,
        name: "gateway-route",
        dataViewId: GATEWAY_DATA_VIEW,
        title: "Gateway route",
        field: "gateway.routeId.keyword",
      }),
    ],
  ),
  [gatewayAnomaliesId]: dashboard(
    "[PromptHub] Gateway Anomalies",
    "Gateway-focused view for authentication failures, routing misses, upstream errors, and slow requests.",
    gatewayAnomaliesPanels,
    [
      timeSlider(gatewayAnomaliesId),
      optionsListControl({
        dashboardId: gatewayAnomaliesId,
        name: "gateway-route",
        dataViewId: GATEWAY_DATA_VIEW,
        title: "Gateway route",
        field: "gateway.routeId.keyword",
      }),
      optionsListControl({
        dashboardId: gatewayAnomaliesId,
        name: "http-status",
        dataViewId: GATEWAY_DATA_VIEW,
        title: "HTTP status",
        field: "gateway.status",
        width: "small",
      }),
    ],
  ),
  [runtimeIncidentsId]: dashboard(
    "[PromptHub] Runtime Incidents",
    "Application-focused view for WARN/ERROR trends, affected services and Pods, stack traces, and startup failures.",
    runtimeIncidentsPanels,
    [
      timeSlider(runtimeIncidentsId),
      optionsListControl({
        dashboardId: runtimeIncidentsId,
        name: "application-service",
        dataViewId: APP_DATA_VIEW,
        title: "Application service",
        field: "service.name",
      }),
      optionsListControl({
        dashboardId: runtimeIncidentsId,
        name: "log-level",
        dataViewId: APP_DATA_VIEW,
        title: "Log level",
        field: "level",
        width: "small",
      }),
    ],
  ),
};

const check = process.argv.includes("--check");

if (!check) {
  mkdirSync(outputDirectory, { recursive: true });
}

for (const [id, payload] of Object.entries(dashboards)) {
  const output = `${JSON.stringify(payload, null, 2)}\n`;
  const path = resolve(outputDirectory, `${id}.json`);

  if (check) {
    if (readFileSync(path, "utf8") !== output) {
      throw new Error(`generated dashboard is stale: ${path}`);
    }
  } else {
    writeFileSync(path, output, "utf8");
  }
}

console.log(
  `${check ? "Validated" : "Generated"} ${Object.keys(dashboards).length} Kibana dashboards.`,
);
