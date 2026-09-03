# How to use the Aikido plugin

This plugin lets you automate [Aikido Security](https://www.aikido.dev/) — scanning code repositories, containers,
and domains for vulnerabilities; triaging and exporting security issues; retrieving compliance reports; and
reacting to new critical findings in real time. See the [Aikido public API documentation](https://apidocs.aikido.dev)
for the full underlying API reference.

## Authentication

Every task and the trigger authenticate with an Aikido OAuth2 API client:

- `clientId` (required, [secret](https://kestra.io/docs/concepts/secret)) — client ID of an Aikido API client scoped
  to the operations being used (for example `issues:read`, `repositories:write`).
- `clientSecret` (required, [secret](https://kestra.io/docs/concepts/secret)) — client secret of that API client.
- `baseUrl` (optional) — defaults to `https://app.aikido.dev` (Europe). Use `https://app.us.aikido.dev` (United
  States), `https://app.au.aikido.dev` (Australia), or `https://app.me.aikido.dev` (Middle East) for other account
  regions. The OAuth2 token endpoint is derived from this same host.

Create API clients in the Aikido console under **Settings > API access**. Store `clientId` and `clientSecret` as
[Kestra secrets](https://kestra.io/docs/concepts/secret), or set them once via
[plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) if every task in a namespace shares
the same API client. A Bearer token is acquired on first use and transparently refreshed before it expires — never
cached across flow executions. A `403` response means the API client is missing the scope required for that
operation; the error message names the missing scope.

## Gotchas

- **Scans are fire-and-forget.** Aikido's scan-trigger endpoints (`ScanRepository`, `ScanContainer`, `ScanDomain`)
  return no scan ID and there is no scan-status endpoint. `waitForCompletion` approximates completion by polling the
  resource's `last_scanned_at` timestamp until it advances past its pre-scan value — this observes "a scan
  finished" on the resource, not necessarily "the scan this task started finished"; a concurrent scan on the same
  resource can also satisfy it.
- **`/open-issue-groups` has no server-side severity filter.** `ListOpenIssues.severities` and
  `IssueTrigger.severityThreshold` are applied client-side after fetching. `ExportIssues.filterSeverities` is a
  genuine server-side filter, since the `/issues/export` endpoint supports it directly.
- **`ListCloudAssets` is the one list endpoint that doesn't return a bare array** — its response wraps items in
  `{ assets, totalCount }` and paginates with `limit` instead of `per_page`. This plugin flattens it into the same
  `rows`/`uri`/`size` output shape as every other list task.
- **`ScanRepository` requires an active repository.** Aikido rejects a scan on an inactive repository with a
  `400` — the task surfaces this verbatim, telling you to activate the repository in the Aikido console first.
- **SBOM export requires a completed scan.** `ExportRepositorySbom` and `ExportContainerSbom` fail with an
  actionable message (rather than storing an empty/error file) if the resource has no completed scan yet.

## Tasks

### Issues (`io.kestra.plugin.aikido.issues`)

- `ListOpenIssues` — lists open issue groups with pagination and `fetchType` support (`FETCH`, `FETCH_ONE`,
  `STORE`, `NONE`); filters client-side on `severities`.
- `GetIssue` — fetches full details of a single issue group.
- `ExportIssues` — exports issues in JSON (fully paginated, honors `fetchType`) or CSV (single non-paginated file,
  always stored) format.
- `SnoozeIssue` / `UnsnoozeIssue` — snooze an issue group until a future point in time (optionally with a reason),
  or cancel an active snooze.

### Repositories (`io.kestra.plugin.aikido.repositories`)

- `ListRepositories` — lists connected code repositories.
- `ScanRepository` — triggers a SAST/IaC/secrets scan (all three enabled by default); supports `waitForCompletion`.
- `ExportRepositorySbom` — exports a repository's license/SBOM report as CSV, CycloneDX, or SPDX.

### Containers (`io.kestra.plugin.aikido.containers`)

- `ListContainers` — lists connected container repositories.
- `ScanContainer` — triggers a container rescan; supports `waitForCompletion`.
- `ExportContainerSbom` — exports a container's license/SBOM report as CSV, CycloneDX, or SPDX.

### Domains (`io.kestra.plugin.aikido.domains`)

- `ListDomains` — lists domains connected to Aikido's surface/DAST monitoring.
- `ScanDomain` — triggers a DAST scan on a connected domain; supports `waitForCompletion`.

### Clouds (`io.kestra.plugin.aikido.clouds`)

- `ListClouds` — lists connected cloud environments.
- `ListCloudAssets` — lists discovered cloud assets, with filtering by type/region/provider/cloud and `fetchType`
  support for large inventories.

### Compliance (`io.kestra.plugin.aikido.compliance`)

- `GetComplianceReport` — fetches the rule-by-rule compliance overview for `NIS2`, `SOC2`, or `ISO27001`.

## Triggers

`issues.IssueTrigger` polls `/open-issue-groups` at the configured `interval` and fires one execution per newly
discovered issue group above `severityThreshold` (oldest first, one per poll cycle). It tracks the `first_detected_at`
of the newest delivered issue group in the flow's namespace KV store to avoid re-firing. On the first poll, only the
baseline is recorded — no execution is fired — to avoid flooding on initial activation. Output includes
`issueGroupId`, `issueType`, `severityScore`, `severity`, `title`, and `firstDetectedAt`.
