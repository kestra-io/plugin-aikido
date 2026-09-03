package io.kestra.plugin.aikido.issues;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.aikido.AbstractAikidoTask;
import io.kestra.plugin.aikido.AikidoClient;
import io.kestra.plugin.aikido.AikidoPagination;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Export Aikido issues",
    description = """
        Exports issues matching the given filters. With `format=JSON` (the default), pages through every \
        matching issue and honors `fetchType` (`FETCH`, `FETCH_ONE`, `STORE`, or `NONE`). With `format=CSV`, \
        Aikido returns a single non-paginated response, so the raw CSV is always stored to internal storage \
        regardless of `fetchType` — use `format=JSON` with `fetchType=FETCH` for a fully paginated, typed export.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Export all open issues and store for compliance reporting",
            full = true,
            code = """
                id: aikido_weekly_issue_export
                namespace: company.security

                tasks:
                  - id: export
                    type: io.kestra.plugin.aikido.issues.ExportIssues
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    fetchType: STORE
                    format: JSON

                  - id: log_summary
                    type: io.kestra.plugin.core.log.Log
                    message: "Exported {{ outputs.export.size }} security issues — stored at {{ outputs.export.uri }}"
                """
        )
    }
)
public class ExportIssues extends AbstractAikidoTask implements RunnableTask<ExportIssues.Output> {
    private static final int PAGE_SIZE = 100;
    private static final String CSV_TRUNCATION_WARNING = "ExportIssues with format=CSV requested {} rows worth of data in a single non-paginated call; the export may be truncated. Use format=JSON for a fully paginated export.";

    @Schema(title = "Export format", description = "`JSON` supports full pagination and `fetchType`; `CSV` returns a single non-paginated file. Defaults to `JSON`.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<ExportFormat> format = Property.ofValue(ExportFormat.JSON);

    @Schema(title = "Filter by issue status")
    @PluginProperty(group = "processing")
    private Property<String> filterStatus;

    @Schema(title = "Only include issues at these severities")
    @PluginProperty(group = "processing")
    private Property<List<Severity>> filterSeverities;

    @Schema(title = "Filter by issue type")
    @PluginProperty(group = "processing")
    private Property<String> filterIssueType;

    @Schema(title = "Filter by team ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterTeamId;

    @Schema(title = "Filter by code repository ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterCodeRepoId;

    @Schema(title = "Filter by container repository ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterContainerRepoId;

    @Schema(title = "Filter by domain ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterDomainId;

    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` outputs the first matching issue, `FETCH` outputs every matching issue, `STORE` streams every matching issue to internal storage and returns a URI. Ignored when `format=CSV` (always behaves like `STORE`). Defaults to `STORE`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFormat = runContext.render(format).as(ExportFormat.class).orElse(ExportFormat.JSON);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);
        var query = baseQuery(runContext);

        runContext.logger().info("Exporting Aikido issues with format {}", rFormat);

        try (var client = client(runContext)) {
            if (rFormat == ExportFormat.CSV) {
                return exportCsv(runContext, client, query);
            }
            return switch (rFetchType) {
                case FETCH_ONE -> fetchOneJson(client, query);
                case STORE -> storeJson(runContext, client, query);
                case FETCH -> {
                    var all = new ArrayList<IssueExportRecord>();
                    var size = AikidoPagination.walk(PAGE_SIZE, page -> fetchPage(client, page, query), all::addAll);
                    yield Output.builder().size(size).rows(all).build();
                }
                case NONE -> {
                    var size = AikidoPagination.walk(PAGE_SIZE, page -> fetchPage(client, page, query), page -> { });
                    yield Output.builder().size(size).build();
                }
            };
        }
    }

    private Output fetchOneJson(AikidoClient client, Map<String, Object> query) throws Exception {
        var page = fetchPage(client, 0, query);
        return page.isEmpty()
            ? Output.builder().size(0L).build()
            : Output.builder().size(1L).row(page.getFirst()).build();
    }

    private Output storeJson(RunContext runContext, AikidoClient client, Map<String, Object> query) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        long size;
        try (var writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            size = AikidoPagination.walk(PAGE_SIZE, page -> fetchPage(client, page, query), page -> {
                if (!page.isEmpty()) {
                    FileSerde.writeAll(writer, Flux.fromIterable(page)).block();
                }
            });
        }
        return Output.builder().size(size).uri(runContext.storage().putFile(tempFile)).build();
    }

    private Output exportCsv(RunContext runContext, AikidoClient client, Map<String, Object> query) throws Exception {
        var withFormat = new LinkedHashMap<>(query);
        withFormat.put("format", "csv");
        withFormat.put("page", 0);
        withFormat.put("per_page", PAGE_SIZE);

        var tempFile = runContext.workingDir().createTempFile(".csv").toFile();
        client.getStream("/issues/export", withFormat, "issues:read", inputStream -> {
            try (var out = new FileOutputStream(tempFile)) {
                inputStream.transferTo(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        long lines;
        try (var reader = new BufferedReader(new FileReader(tempFile, StandardCharsets.UTF_8))) {
            lines = reader.lines().count();
        }
        if (lines >= PAGE_SIZE) {
            runContext.logger().warn(CSV_TRUNCATION_WARNING, lines);
        }
        var uri = runContext.storage().putFile(tempFile, "aikido-issues-export.csv");
        return Output.builder().size(Math.max(lines - 1, 0)).uri(uri).build();
    }

    private List<IssueExportRecord> fetchPage(AikidoClient client, int page, Map<String, Object> query) throws Exception {
        var withPage = new LinkedHashMap<>(query);
        withPage.put("page", page);
        withPage.put("per_page", PAGE_SIZE);
        return client.getArray("/issues/export", withPage, "issues:read", IssueExportRecord.class);
    }

    private Map<String, Object> baseQuery(RunContext runContext) throws Exception {
        var rFilterStatus = runContext.render(filterStatus).as(String.class).orElse(null);
        var rFilterSeverities = runContext.render(filterSeverities).asList(Severity.class);
        var rFilterIssueType = runContext.render(filterIssueType).as(String.class).orElse(null);
        var rFilterTeamId = runContext.render(filterTeamId).as(Long.class).orElse(null);
        var rFilterCodeRepoId = runContext.render(filterCodeRepoId).as(Long.class).orElse(null);
        var rFilterContainerRepoId = runContext.render(filterContainerRepoId).as(Long.class).orElse(null);
        var rFilterDomainId = runContext.render(filterDomainId).as(Long.class).orElse(null);

        var query = new LinkedHashMap<String, Object>();
        if (rFilterStatus != null && !rFilterStatus.isBlank()) {
            query.put("filter_status", rFilterStatus);
        }
        if (!rFilterSeverities.isEmpty()) {
            query.put("filter_severities", rFilterSeverities.stream().map(s -> s.name().toLowerCase()).toList());
        }
        if (rFilterIssueType != null && !rFilterIssueType.isBlank()) {
            query.put("filter_issue_type", rFilterIssueType);
        }
        if (rFilterTeamId != null) {
            query.put("filter_team_id", rFilterTeamId);
        }
        if (rFilterCodeRepoId != null) {
            query.put("filter_code_repo_id", rFilterCodeRepoId);
        }
        if (rFilterContainerRepoId != null) {
            query.put("filter_container_repo_id", rFilterContainerRepoId);
        }
        if (rFilterDomainId != null) {
            query.put("filter_domain_id", rFilterDomainId);
        }
        return query;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of issues exported")
        private final Long size;

        @Schema(title = "Exported issues", description = "Populated only when `format=JSON` and `fetchType=FETCH`.")
        private final List<IssueExportRecord> rows;

        @Schema(title = "First exported issue", description = "Populated only when `format=JSON` and `fetchType=FETCH_ONE`.")
        private final IssueExportRecord row;

        @Schema(title = "Stored data URI", description = "Populated when `fetchType=STORE`, or always when `format=CSV`.")
        private final URI uri;
    }
}
