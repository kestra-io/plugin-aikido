package io.kestra.plugin.aikido.issues;

/**
 * Human-friendly Aikido issue severity. {@code ExportIssues} passes this as the server-side
 * `filter_severities` query parameter; {@code ListOpenIssues} has no equivalent server-side filter on
 * the open issue groups endpoint, so it filters client-side on {@code severity} after fetching.
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
