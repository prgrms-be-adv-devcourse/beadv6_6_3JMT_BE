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
    axis: xyAxis("응답 시간 (ms)"),
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
    title: "전체 요청 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    color: "#6092c0",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "2xx-responses",
    title: "2xx 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 8, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 200 and gateway.status < 300",
    color: "#209280",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "4xx-responses",
    title: "4xx 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 16, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 400 and gateway.status < 500",
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "5xx-responses",
    title: "5xx 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 500 and gateway.status < 600",
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "p95-latency",
    title: "p95 지연 시간",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 32, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    operation: "percentile",
    field: "gateway.durationMs",
    percentile: 95,
    color: "#9170b8",
    subtitle: "밀리초 (ms)",
  }),
  metricPanel({
    dashboardId: serviceHealthId,
    name: "application-errors",
    title: "ERROR 로그 수",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 40, y: 0, w: 8, h: 6 },
    filter: 'level: "ERROR"',
    color: "#cc5642",
  }),
  xyTermsPanel({
    dashboardId: serviceHealthId,
    name: "request-volume-by-route",
    title: "게이트웨이 라우트별 요청량",
    description: "게이트웨이 요청량을 라우트 ID별 시계열로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 6, w: 24, h: 12 },
    field: "gateway.routeId.keyword",
    fieldLabel: "게이트웨이 라우트",
    query: GATEWAY_EVENT_QUERY,
    yLabel: "요청 수",
  }),
  xyFiltersPanel({
    dashboardId: serviceHealthId,
    name: "responses-by-status-class",
    title: "상태 코드 구간별 HTTP 응답",
    description: "게이트웨이 응답을 성공 및 오류 상태 코드 구간별 시계열로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 6, w: 24, h: 12 },
    query: GATEWAY_EVENT_QUERY,
    breakdownLabel: "상태 코드 구간",
    yLabel: "응답 수",
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
    title: "게이트웨이 응답 시간 백분위수",
    description: "게이트웨이 요청의 p50, p95, p99 응답 시간을 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 18, w: 48, h: 12 },
    field: "gateway.durationMs",
    query: GATEWAY_EVENT_QUERY,
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "slowest-routes",
    title: "p95 지연이 높은 게이트웨이 라우트 TOP 10",
    description: "p95 응답 시간이 높은 라우트 순으로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 30, w: 24, h: 14 },
    query: GATEWAY_EVENT_QUERY,
    metric: tableMetric({
      operation: "percentile",
      field: "gateway.durationMs",
      percentile: 95,
      label: "p95 지연 시간 (ms)",
    }),
    rows: [
      tableRow("gateway.routeId.keyword", "게이트웨이 라우트", 10),
      tableRow("gateway.method.keyword", "HTTP 메서드", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "server-error-paths",
    title: "5xx 응답이 많은 요청 경로 TOP 10",
    description: "서버 오류 응답 수가 많은 게이트웨이 요청 경로 순으로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 30, w: 24, h: 14 },
    query: `${GATEWAY_EVENT_QUERY} and gateway.status >= 500 and gateway.status < 600`,
    metric: tableMetric({
      operation: "count",
      label: "5xx 응답 수",
    }),
    rows: [
      tableRow("gateway.path.keyword", "요청 경로", 10),
      tableRow("gateway.routeId.keyword", "게이트웨이 라우트", 1, false),
      tableRow("gateway.method.keyword", "HTTP 메서드", 1, false),
    ],
  }),
  xyTermsPanel({
    dashboardId: serviceHealthId,
    name: "warning-error-by-service",
    title: "서비스별 WARN·ERROR 로그",
    description: "잠재적 장애 신호를 애플리케이션 서비스별 시계열로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 44, w: 48, h: 12 },
    field: "service.name",
    fieldLabel: "애플리케이션 서비스",
    query: 'level: ("WARN" or "ERROR")',
    yLabel: "WARN·ERROR 로그 수",
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "error-loggers",
    title: "ERROR 로그 상위 로거",
    description: "ERROR 발생 횟수가 많은 로거 순으로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 56, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR 로그 수",
    }),
    rows: [
      tableRow("logger_name", "로거", 10),
      tableRow("service.name", "애플리케이션 서비스", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: serviceHealthId,
    name: "error-pods",
    title: "ERROR 로그 상위 Pod",
    description: "ERROR 발생 횟수가 많은 Kubernetes Pod 순으로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 56, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR 로그 수",
    }),
    rows: [
      tableRow("kubernetes.pod_name", "Pod", 10),
      tableRow("service.name", "애플리케이션 서비스", 1, false),
      tableRow("logger_name", "로거", 1, false),
    ],
  }),
];

const gatewayAnomaliesId = "prompthub-gateway-anomalies";
const gatewayAnomaliesPanels = [
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "401-responses",
    title: "401 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status: 401",
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "403-responses",
    title: "403 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 8, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status: 403",
    color: "#da8b45",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "404-responses",
    title: "404 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 16, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status: 404",
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "5xx-responses",
    title: "5xx 응답 수",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.status >= 500 and gateway.status < 600",
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "unknown-route",
    title: "미매칭 라우트 (UNKNOWN)",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 32, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: 'gateway.routeId: "UNKNOWN"',
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: gatewayAnomaliesId,
    name: "slow-requests",
    title: "1000ms 이상 지연 요청",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 40, y: 0, w: 8, h: 6 },
    query: GATEWAY_EVENT_QUERY,
    filter: "gateway.durationMs >= 1000",
    color: "#9170b8",
  }),
  xyFiltersPanel({
    dashboardId: gatewayAnomaliesId,
    name: "error-status-trend",
    title: "상태 코드별 게이트웨이 오류 응답",
    description: "인증, 라우팅, 업스트림 오류를 상태 코드별 시계열로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 6, w: 24, h: 12 },
    query: GATEWAY_EVENT_QUERY,
    breakdownLabel: "HTTP 상태 코드",
    yLabel: "응답 수",
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
    title: "미매칭 라우트와 404 추이",
    description: "미매칭 라우트와 전체 404 응답을 비교합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 6, w: 24, h: 12 },
    query: GATEWAY_EVENT_QUERY,
    breakdownLabel: "라우팅 이상 유형",
    yLabel: "응답 수",
    filters: [
      { expression: 'gateway.routeId: "UNKNOWN"', label: "미매칭 라우트 (UNKNOWN)" },
      { expression: "gateway.status: 404", label: "404" },
    ],
  }),
  xyTermsPanel({
    dashboardId: gatewayAnomaliesId,
    name: "slow-request-trend",
    title: "라우트별 지연 요청",
    description: "1초 이상 걸린 요청을 라우트별 시계열로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 18, w: 24, h: 12 },
    field: "gateway.routeId.keyword",
    fieldLabel: "게이트웨이 라우트",
    query: `${GATEWAY_EVENT_QUERY} and gateway.durationMs >= 1000`,
    yLabel: "지연 요청 수",
  }),
  percentilePanel({
    dashboardId: gatewayAnomaliesId,
    name: "latency-percentiles",
    title: "게이트웨이 지연 시간 백분위수",
    description: "이상 징후와 비교할 수 있도록 p50, p95, p99 응답 시간을 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 18, w: 24, h: 12 },
    field: "gateway.durationMs",
    query: GATEWAY_EVENT_QUERY,
  }),
  dataTablePanel({
    dashboardId: gatewayAnomaliesId,
    name: "unknown-and-404-paths",
    title: "미매칭 및 404 요청 경로",
    description: "라우팅 누락과 가장 자주 연관된 요청 경로 순으로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 30, w: 24, h: 14 },
    query: `${GATEWAY_EVENT_QUERY} and (gateway.routeId: "UNKNOWN" or gateway.status: 404)`,
    metric: tableMetric({
      operation: "count",
      label: "응답 수",
    }),
    rows: [
      tableRow("gateway.path.keyword", "요청 경로", 10),
      tableRow("gateway.status", "상태 코드", 3, false),
      tableRow("gateway.method.keyword", "HTTP 메서드", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: gatewayAnomaliesId,
    name: "p95-by-route",
    title: "p95 지연이 높은 라우트",
    description: "p95 응답 시간이 높은 게이트웨이 라우트 순으로 표시합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 30, w: 24, h: 14 },
    query: GATEWAY_EVENT_QUERY,
    metric: tableMetric({
      operation: "percentile",
      field: "gateway.durationMs",
      percentile: 95,
      label: "p95 지연 시간 (ms)",
    }),
    rows: [
      tableRow("gateway.routeId.keyword", "게이트웨이 라우트", 10),
      tableRow("gateway.method.keyword", "HTTP 메서드", 1, false),
    ],
  }),
  xyTermsPanel({
    dashboardId: gatewayAnomaliesId,
    name: "authenticated-requests",
    title: "인증 여부별 요청",
    description: "게이트웨이 요청을 인증 여부별로 구분합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 0, y: 44, w: 24, h: 12 },
    field: "gateway.authenticated",
    fieldLabel: "인증 여부",
    query: GATEWAY_EVENT_QUERY,
    yLabel: "요청 수",
    limit: 2,
  }),
  xyTermsPanel({
    dashboardId: gatewayAnomaliesId,
    name: "requests-by-method",
    title: "HTTP 메서드별 요청",
    description: "게이트웨이 요청을 HTTP 메서드별로 구분합니다.",
    dataViewId: GATEWAY_DATA_VIEW,
    grid: { x: 24, y: 44, w: 24, h: 12 },
    field: "gateway.method.keyword",
    fieldLabel: "HTTP 메서드",
    query: GATEWAY_EVENT_QUERY,
    yLabel: "요청 수",
    limit: 10,
  }),
];

const runtimeIncidentsId = "prompthub-runtime-incidents";
const runtimeIncidentsPanels = [
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "warning-logs",
    title: "WARN 로그 수",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 0, w: 8, h: 6 },
    filter: 'level: "WARN"',
    color: "#d6bf57",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "error-logs",
    title: "ERROR 로그 수",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 8, y: 0, w: 8, h: 6 },
    filter: 'level: "ERROR"',
    color: "#cc5642",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "affected-services",
    title: "영향받은 서비스 수",
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
    title: "영향받은 Pod 수",
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
    title: "스택 트레이스 포함 ERROR",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 32, y: 0, w: 8, h: 6 },
    filter: 'level: "ERROR" and stack_trace: *',
    color: "#9170b8",
  }),
  metricPanel({
    dashboardId: runtimeIncidentsId,
    name: "startup-errors",
    title: "기동 단계 ERROR",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 40, y: 0, w: 8, h: 6 },
    filter: STARTUP_ERROR_QUERY,
    color: "#cc5642",
  }),
  xyTermsPanel({
    dashboardId: runtimeIncidentsId,
    name: "logs-by-level",
    title: "레벨별 애플리케이션 로그",
    description: "구조화된 애플리케이션 로그를 레벨별 시계열로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 6, w: 24, h: 12 },
    field: "level",
    fieldLabel: "로그 레벨",
    yLabel: "로그 건수",
    limit: 6,
  }),
  xyTermsPanel({
    dashboardId: runtimeIncidentsId,
    name: "warning-error-by-service",
    title: "서비스별 WARN·ERROR 로그",
    description: "잠재적 장애 신호를 애플리케이션 서비스별 시계열로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 6, w: 24, h: 12 },
    field: "service.name",
    fieldLabel: "애플리케이션 서비스",
    query: 'level: ("WARN" or "ERROR")',
    yLabel: "WARN·ERROR 로그 수",
  }),
  xyTermsPanel({
    dashboardId: runtimeIncidentsId,
    name: "errors-by-pod",
    title: "Kubernetes Pod별 ERROR 로그",
    description: "격리된 장애를 식별할 수 있도록 ERROR 로그를 Pod별 시계열로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 18, w: 48, h: 12 },
    field: "kubernetes.pod_name",
    fieldLabel: "Pod",
    query: 'level: "ERROR"',
    yLabel: "ERROR 로그 수",
  }),
  dataTablePanel({
    dashboardId: runtimeIncidentsId,
    name: "error-loggers",
    title: "ERROR 로그 상위 로거",
    description: "ERROR 발생 횟수가 많은 로거 순으로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 30, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR 로그 수",
    }),
    rows: [
      tableRow("logger_name", "로거", 10),
      tableRow("service.name", "애플리케이션 서비스", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: runtimeIncidentsId,
    name: "error-service-pods",
    title: "ERROR 발생 서비스와 Pod",
    description: "ERROR 발생 횟수가 많은 서비스와 Pod 순으로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 24, y: 30, w: 24, h: 14 },
    query: 'level: "ERROR"',
    metric: tableMetric({
      operation: "count",
      label: "ERROR 로그 수",
    }),
    rows: [
      tableRow("service.name", "애플리케이션 서비스", 10),
      tableRow("kubernetes.pod_name", "Pod", 3, false),
      tableRow("logger_name", "로거", 1, false),
    ],
  }),
  dataTablePanel({
    dashboardId: runtimeIncidentsId,
    name: "startup-error-loggers",
    title: "기동 단계 ERROR 상위 로거",
    description: "main 스레드와 Spring 기동 로거를 ERROR 발생 횟수 순으로 표시합니다.",
    dataViewId: APP_DATA_VIEW,
    grid: { x: 0, y: 44, w: 48, h: 14 },
    query: STARTUP_ERROR_QUERY,
    metric: tableMetric({
      operation: "count",
      label: "기동 단계 ERROR 로그 수",
    }),
    rows: [
      tableRow("logger_name", "로거", 10),
      tableRow("service.name", "애플리케이션 서비스", 5, false),
      tableRow("kubernetes.pod_name", "Pod", 3, false),
      tableRow("thread_name", "스레드", 1, false),
    ],
  }),
];

const dashboards = {
  [serviceHealthId]: dashboard(
    "[PromptHub] 서비스 상태",
    "PromptHub 전체 요청량, HTTP 상태, 게이트웨이 지연 시간과 애플리케이션 WARN·ERROR 신호를 한 화면에서 확인합니다.",
    serviceHealthPanels,
    [
      timeSlider(serviceHealthId),
      optionsListControl({
        dashboardId: serviceHealthId,
        name: "application-service",
        dataViewId: APP_DATA_VIEW,
        title: "애플리케이션 서비스",
        field: "service.name",
      }),
      optionsListControl({
        dashboardId: serviceHealthId,
        name: "gateway-route",
        dataViewId: GATEWAY_DATA_VIEW,
        title: "게이트웨이 라우트",
        field: "gateway.routeId.keyword",
      }),
    ],
  ),
  [gatewayAnomaliesId]: dashboard(
    "[PromptHub] 게이트웨이 이상 징후",
    "게이트웨이 인증 실패, 라우팅 누락, 업스트림 오류와 지연 요청을 분석합니다.",
    gatewayAnomaliesPanels,
    [
      timeSlider(gatewayAnomaliesId),
      optionsListControl({
        dashboardId: gatewayAnomaliesId,
        name: "gateway-route",
        dataViewId: GATEWAY_DATA_VIEW,
        title: "게이트웨이 라우트",
        field: "gateway.routeId.keyword",
      }),
      optionsListControl({
        dashboardId: gatewayAnomaliesId,
        name: "http-status",
        dataViewId: GATEWAY_DATA_VIEW,
        title: "HTTP 상태 코드",
        field: "gateway.status",
        width: "small",
      }),
    ],
  ),
  [runtimeIncidentsId]: dashboard(
    "[PromptHub] 런타임 장애 분석",
    "애플리케이션 WARN·ERROR 추이, 영향 서비스와 Pod, 스택 트레이스 및 기동 실패를 분석합니다.",
    runtimeIncidentsPanels,
    [
      timeSlider(runtimeIncidentsId),
      optionsListControl({
        dashboardId: runtimeIncidentsId,
        name: "application-service",
        dataViewId: APP_DATA_VIEW,
        title: "애플리케이션 서비스",
        field: "service.name",
      }),
      optionsListControl({
        dashboardId: runtimeIncidentsId,
        name: "log-level",
        dataViewId: APP_DATA_VIEW,
        title: "로그 레벨",
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
