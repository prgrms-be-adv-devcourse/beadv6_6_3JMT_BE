#!/usr/bin/env ruby

require "json"
require "open3"
require "yaml"

ROOT_DIR = File.expand_path("..", __dir__)
DASHBOARD_DIR = File.join(ROOT_DIR, "k8s/addons/elk/dashboards")

DASHBOARD_CONTRACTS = {
  "prompthub-service-health" => {
    data_views: %w[gateway-access application-logs],
    required_terms: [
      "gateway.status",
      "gateway.durationMs",
      "gateway.routeId.keyword",
      "level",
      "service.name",
      "kubernetes.pod_name",
      "logger_name",
    ],
  },
  "prompthub-gateway-anomalies" => {
    data_views: %w[gateway-access],
    required_terms: [
      "gateway.status",
      "gateway.durationMs",
      "gateway.routeId.keyword",
      'gateway.routeId: "UNKNOWN"',
    ],
  },
  "prompthub-runtime-incidents" => {
    data_views: %w[application-logs],
    required_terms: [
      "level",
      "service.name",
      "kubernetes.pod_name",
      "logger_name",
      "stack_trace",
    ],
  },
}.freeze

REQUIRED_ROOT_KEYS = %w[
  title
  description
  options
  time_range
  filters
  query
  panels
  pinned_panels
].freeze

ALLOWED_VISUALIZATION_TYPES = %w[metric xy data_table].freeze
FORBIDDEN_TERMS = [
  /payment[-_ ]audit/i,
  /\berrorCode\b/,
  /\berrorType\b/,
].freeze

def each_object(value, &block)
  case value
  when Hash
    yield value
    value.each_value { |child| each_object(child, &block) }
  when Array
    value.each { |child| each_object(child, &block) }
  end
end

def referenced_data_views(dashboard)
  references = []

  each_object(dashboard) do |object|
    if object["type"] == "data_view_reference"
      references << object["ref_id"]
    end
    references << object["data_view_id"] if object.key?("data_view_id")
  end

  references.compact.uniq
end

def all_strings(value)
  case value
  when Hash
    value.values.flat_map { |child| all_strings(child) }
  when Array
    value.flat_map { |child| all_strings(child) }
  when String
    [value]
  else
    []
  end
end

def validate_dashboard(id, contract, errors)
  path = File.join(DASHBOARD_DIR, "#{id}.json")
  unless File.file?(path)
    errors << "missing dashboard: #{path}"
    return
  end

  dashboard = JSON.parse(File.read(path))
  missing_keys = REQUIRED_ROOT_KEYS - dashboard.keys
  errors << "#{id}: missing root keys #{missing_keys.join(", ")}" unless missing_keys.empty?
  errors << "#{id}: title is required" if dashboard["title"].to_s.empty?
  errors << "#{id}: description is required" if dashboard["description"].to_s.empty?

  expected_time_range = { "from" => "now-24h", "to" => "now" }
  unless dashboard["time_range"] == expected_time_range
    errors << "#{id}: default time range must be now-24h to now"
  end

  panels = dashboard["panels"]
  unless panels.is_a?(Array) && !panels.empty?
    errors << "#{id}: panels must not be empty"
    return
  end

  invalid_panel_types = panels.map do |panel|
    panel["type"] unless panel["type"] == "vis"
  end.compact.uniq
  unless invalid_panel_types.empty?
    errors << "#{id}: invalid panel types #{invalid_panel_types.join(", ")}"
  end

  invalid_visualizations = panels.map do |panel|
    visualization_type = panel.dig("config", "type")
    visualization_type unless ALLOWED_VISUALIZATION_TYPES.include?(visualization_type)
  end.compact.uniq
  unless invalid_visualizations.empty?
    errors << "#{id}: invalid visualization types #{invalid_visualizations.join(", ")}"
  end

  panel_ids = (panels + Array(dashboard["pinned_panels"])).map { |panel| panel["id"] }.compact
  panel_id_counts = panel_ids.each_with_object(Hash.new(0)) do |panel_id, counts|
    counts[panel_id] += 1
  end
  duplicate_ids = panel_id_counts.select { |_, count| count > 1 }.keys
  errors << "#{id}: duplicate panel IDs #{duplicate_ids.join(", ")}" unless duplicate_ids.empty?

  each_object(dashboard) do |object|
    next unless object.key?("expression") || object.key?("language")

    unless object["language"] == "kql" && object["expression"].is_a?(String)
      errors << "#{id}: every query expression must be a KQL string"
    end
  end

  references = referenced_data_views(dashboard)
  missing_references = contract.fetch(:data_views) - references
  invalid_references = references - contract.fetch(:data_views)
  unless missing_references.empty?
    errors << "#{id}: missing Data View references #{missing_references.join(", ")}"
  end
  unless invalid_references.empty?
    errors << "#{id}: invalid Data View references #{invalid_references.join(", ")}"
  end

  string_values = all_strings(dashboard)
  contract.fetch(:required_terms).each do |term|
    errors << "#{id}: missing required term #{term}" unless string_values.any? { |value| value.include?(term) }
  end
  FORBIDDEN_TERMS.each do |term|
    errors << "#{id}: out-of-scope term #{term.inspect}" if string_values.any? { |value| value.match?(term) }
  end
end

def validate_gateway_data_view(errors)
  path = File.join(DASHBOARD_DIR, "gateway-data-view.ndjson")
  unless File.file?(path)
    errors << "missing Gateway Data View: #{path}"
    return
  end

  lines = File.readlines(path, chomp: true).reject(&:empty?)
  unless lines.length == 1
    errors << "Gateway Data View NDJSON must contain exactly one object"
    return
  end

  data_view = JSON.parse(lines.first)
  expected = {
    "type" => "index-pattern",
    "id" => "gateway-access",
    "attributes" => {
      "title" => "gateway-access-*",
      "name" => "Gateway Access",
      "timeFieldName" => "@timestamp",
    },
  }
  errors << "Gateway Data View contract does not match #{expected.inspect}" unless data_view == expected
end

def validate_rendered_kubernetes_assets(errors)
  elk_directory = File.join(ROOT_DIR, "k8s/addons/elk")
  output, error_output, status = Open3.capture3("kubectl", "kustomize", elk_directory)
  unless status.success?
    errors << "failed to render ELK manifests: #{error_output.strip}"
    return
  end

  resources = YAML.load_stream(output).compact
  config_map = resources.find do |resource|
    resource["kind"] == "ConfigMap" &&
      resource.dig("metadata", "name") == "kibana-operations-dashboards"
  end
  job = resources.find do |resource|
    resource["kind"] == "Job" &&
      resource.dig("metadata", "name") == "kibana-operations-dashboards-bootstrap"
  end

  unless config_map
    errors << "rendered ELK package is missing kibana-operations-dashboards ConfigMap"
    return
  end
  unless job
    errors << "rendered ELK package is missing kibana-operations-dashboards-bootstrap Job"
    return
  end

  expected_files = [
    "gateway-data-view.ndjson",
    "prompthub-service-health.json",
    "prompthub-gateway-anomalies.json",
    "prompthub-runtime-incidents.json",
  ]
  missing_files = expected_files - config_map.fetch("data", {}).keys
  unless missing_files.empty?
    errors << "dashboard ConfigMap is missing files #{missing_files.join(", ")}"
  end

  pod_spec = job.dig("spec", "template", "spec") || {}
  container = Array(pod_spec["containers"]).find { |candidate| candidate["name"] == "bootstrap" }
  unless container
    errors << "dashboard bootstrap Job is missing the bootstrap container"
    return
  end

  command = Array(container["command"]).join("\n")
  expected_command_terms = [
    "/api/status",
    "/api/saved_objects/_import?overwrite=true",
    "/api/saved_objects/index-pattern/application-logs",
    "/api/dashboards/${dashboard_id}",
    "gateway-data-view.ndjson",
    "prompthub-service-health",
    "prompthub-gateway-anomalies",
    "prompthub-runtime-incidents",
  ]
  expected_command_terms.each do |term|
    errors << "dashboard bootstrap command is missing #{term}" unless command.include?(term)
  end

  errors << "dashboard bootstrap must use PUT" unless command.include?("-X PUT")
  errors << "dashboard bootstrap must fail on HTTP errors" unless command.include?("--fail")
  unless command.include?('"success":true') && command.include?('"successCount":1')
    errors << "dashboard bootstrap must verify the Data View import response"
  end

  volume = Array(pod_spec["volumes"]).find { |candidate| candidate["name"] == "dashboards" }
  unless volume&.dig("configMap", "name") == "kibana-operations-dashboards"
    errors << "dashboard bootstrap must mount the generated ConfigMap"
  end
end

errors = []

DASHBOARD_CONTRACTS.each do |id, contract|
  validate_dashboard(id, contract, errors)
rescue JSON::ParserError => error
  errors << "#{id}: invalid JSON: #{error.message}"
end

validate_gateway_data_view(errors)
validate_rendered_kubernetes_assets(errors)

unless errors.empty?
  warn errors.join("\n")
  exit 1
end

puts "Kibana dashboard validation passed."
