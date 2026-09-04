# Kestra Aikido Plugin

## What

- Provides plugin components under `io.kestra.plugin.aikido` for [Aikido Security](https://www.aikido.dev/).
- Sub-packages: `issues`, `repositories`, `containers`, `domains`, `clouds`, `compliance`.
- Tasks: `issues.ListOpen`, `issues.Get`, `issues.Export`, `issues.Snooze`,
  `issues.Unsnooze`, `repositories.List`, `repositories.Scan`,
  `repositories.ExportSbom`, `containers.List`, `containers.Scan`,
  `containers.ExportSbom`, `domains.List`, `domains.Scan`, `clouds.List`,
  `clouds.ListAssets`, `compliance.GetReport`.
- Trigger: `issues.Trigger` (polling; fires on newly detected issue groups above `severityThreshold`).

## Why

- Teams that use Aikido need to embed security scanning, issue triage, and compliance reporting into Kestra flows
  (CI/CD security gates, scheduled compliance exports, automated remediation) without brittle shell scripts or
  custom API glue code.
- This plugin gives Kestra users a first-class way to trigger scans, list and export findings, and react to new
  critical issues in real time, alongside existing `plugin-aws`, `plugin-gcp`, and `plugin-azure` integrations.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.aikido`:

- root: `AbstractAikidoTask`, `AbstractAikidoTrigger`, `AbstractScanTask`, `AikidoClient` (OAuth2 client-credentials
  HTTP client, instance-scoped token cache, pagination-agnostic error mapping), `AikidoPagination` (shared
  page-walking helper), `AikidoConnectionInterface` (shared `@Schema` wording), `SbomFormat` (shared by
  `repositories`/`containers` SBOM exports).
- `issues` — issue listing/export/snooze tasks, `Severity`/`ExportFormat` enums, `IssueGroup`/`Location`/
  `IssueExportRecord` response models, and the `Trigger` polling trigger.
- `repositories` — code repository listing/scan/SBOM export tasks and the `CodeRepository`/`SbomScope` models.
- `containers` — container repository listing/scan/SBOM export tasks and the `Container` model.
- `domains` — domain listing/DAST-scan tasks and the `Domain` model.
- `clouds` — cloud environment and cloud asset listing tasks and the `Cloud`/`CloudAsset` models.
- `compliance` — `GetReport` and the `ComplianceFramework` enum.

Authentication: every task/trigger holds `clientId`/`clientSecret` (both secret) and an optional `baseUrl`
(defaults to `https://app.aikido.dev`, supports the `.us`/`.au`/`.me` regional hosts). `AikidoClient` exchanges
these for a short-lived JWT via `POST {baseUrl}/api/oauth/token`, refreshing at 90% of the token's lifetime. The
token cache is instance-scoped (one `AikidoClient` per task run or trigger poll) — never static/JVM-wide.

Scan tasks (`repositories.Scan`, `containers.Scan`, `domains.Scan`) share `AbstractScanTask`'s `waitForCompletion` /
`pollInterval` / `maxDuration` properties: since Aikido's scan-trigger endpoints are fire-and-forget with no scan ID
or status endpoint, completion is approximated by polling the resource's `last_scanned_at` timestamp until it
advances past its pre-scan baseline.

### Project Structure

```
plugin-aikido/
├── src/main/java/io/kestra/plugin/aikido/
│   ├── issues/
│   ├── repositories/
│   ├── containers/
│   ├── domains/
│   ├── clouds/
│   └── compliance/
├── src/test/java/io/kestra/plugin/aikido/       (mirrors main, WireMock-backed unit tests)
├── src/test/resources/sanity-checks/            (end-to-end flow YAML + RunnerTest)
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Every list task (`issues.ListOpen`, `repositories.List`, `containers.List`, `domains.List`,
  `clouds.List`, `clouds.ListAssets`) shares the same `rows`/`row`/`uri`/`size` output shape driven by `Property<FetchType>`.
- Aikido's OAuth2 scopes are per-operation; `AikidoClient` calls always pass the required scope so a `403` names it
  in the error message.

## References

- https://apidocs.aikido.dev
- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
