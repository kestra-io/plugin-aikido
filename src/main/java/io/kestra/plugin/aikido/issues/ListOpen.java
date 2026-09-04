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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Aikido open issue groups",
    description = """
        Lists open issue groups as seen in the Aikido feed, sorted descending by priority, and pages through \
        every matching result. Aikido's `/open-issue-groups` endpoint has no server-side severity filter, so \
        `severities` is applied client-side after fetching each page. `fetchType=STORE` is recommended for large \
        result sets: it streams results to internal storage instead of holding them all in memory or in the \
        execution output.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "List every open critical or high severity issue",
            full = true,
            code = """
                id: list_critical_issues
                namespace: company.security

                tasks:
                  - id: list_issues
                    type: io.kestra.plugin.aikido.issues.ListOpen
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    severities:
                      - CRITICAL
                      - HIGH
                    fetchType: FETCH
                """
        )
    }
)
public class ListOpen extends AbstractAikidoTask implements RunnableTask<ListOpen.Output> {
    @Schema(title = "Filter by code repository ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterCodeRepoId;

    @Schema(title = "Filter by container repository ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterContainerRepoId;

    @Schema(title = "Filter by team ID")
    @PluginProperty(group = "processing")
    private Property<Long> filterTeamId;

    @Schema(title = "Filter by issue type", description = "For example `open_source`, `leaked_secret`, `sast`, or `iac`.")
    @PluginProperty(group = "processing")
    private Property<String> filterIssueType;

    @Schema(title = "Filter by issue group status", description = "For example `new`, `todo`, or `task_open`.")
    @PluginProperty(group = "processing")
    private Property<String> filterStatus;

    @Schema(title = "Only include issues at these severities", description = "Applied client-side after fetching, since the API has no server-side severity filter on this endpoint. Matches every severity when omitted.")
    @PluginProperty(group = "processing")
    private Property<List<Severity>> severities;

    @Schema(title = "Page size", description = "Internal pagination page size, from 10 to 100. Defaults to `20`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(10) @Max(100) Integer> pageSize = Property.ofValue(20);

    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` outputs the first matching issue group, `FETCH` outputs every matching issue group, `STORE` streams every matching issue group to internal storage and returns a URI. Defaults to `FETCH`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFilterCodeRepoId = runContext.render(filterCodeRepoId).as(Long.class).orElse(null);
        var rFilterContainerRepoId = runContext.render(filterContainerRepoId).as(Long.class).orElse(null);
        var rFilterTeamId = runContext.render(filterTeamId).as(Long.class).orElse(null);
        var rFilterIssueType = runContext.render(filterIssueType).as(String.class).orElse(null);
        var rFilterStatus = runContext.render(filterStatus).as(String.class).orElse(null);
        var rSeverities = runContext.render(severities).asList(Severity.class);
        var rPageSize = runContext.render(pageSize).as(Integer.class).orElse(20);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        var query = baseQuery(rFilterCodeRepoId, rFilterContainerRepoId, rFilterTeamId, rFilterIssueType, rFilterStatus);

        runContext.logger().info("Listing Aikido open issue groups with severities {}", rSeverities.isEmpty() ? "any" : rSeverities);

        try (var client = client(runContext)) {
            return switch (rFetchType) {
                case FETCH_ONE -> fetchOne(client, query, rPageSize, rSeverities);
                case STORE -> store(runContext, client, query, rPageSize, rSeverities);
                case FETCH -> {
                    var all = new ArrayList<IssueGroup>();
                    var size = paginate(client, query, rPageSize, rSeverities, all::addAll);
                    yield Output.builder().size(size).rows(all).build();
                }
                case NONE -> {
                    var count = new AtomicLong();
                    paginate(client, query, rPageSize, rSeverities, page -> count.addAndGet(page.size()));
                    yield Output.builder().size(count.get()).build();
                }
            };
        }
    }

    private Output fetchOne(AikidoClient client, Map<String, Object> query, int pageSize, List<Severity> severities) throws Exception {
        try {
            AikidoPagination.walk(pageSize, page -> fetchPage(client, page, pageSize, query), page -> {
                var filtered = filterSeverities(page, severities);
                if (!filtered.isEmpty()) {
                    throw new FoundSignal(filtered.getFirst());
                }
            });
        } catch (FoundSignal signal) {
            return Output.builder().row(signal.item).size(1L).build();
        }
        return Output.builder().size(0L).build();
    }

    private Output store(RunContext runContext, AikidoClient client, Map<String, Object> query, int pageSize, List<Severity> severities) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        long size;
        try (var writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            size = paginate(client, query, pageSize, severities, page -> {
                if (!page.isEmpty()) {
                    FileSerde.writeAll(writer, Flux.fromIterable(page)).block();
                }
            });
        }
        return Output.builder().size(size).uri(runContext.storage().putFile(tempFile)).build();
    }

    @FunctionalInterface
    private interface PageConsumer {
        void accept(List<IssueGroup> filteredPage) throws Exception;
    }

    /** Streams each filtered page to {@code pageConsumer}; pagination termination is based on the raw (unfiltered) page size. */
    private long paginate(AikidoClient client, Map<String, Object> query, int pageSize, List<Severity> severities, PageConsumer pageConsumer) throws Exception {
        var total = new AtomicLong();
        AikidoPagination.walk(pageSize, page -> fetchPage(client, page, pageSize, query), page -> {
            var filtered = filterSeverities(page, severities);
            total.addAndGet(filtered.size());
            pageConsumer.accept(filtered);
        });
        return total.get();
    }

    private List<IssueGroup> fetchPage(AikidoClient client, int page, int pageSize, Map<String, Object> query) throws Exception {
        var withPage = new LinkedHashMap<>(query);
        withPage.put("page", page);
        withPage.put("per_page", pageSize);
        return client.getArray("/open-issue-groups", withPage, "issues:read", IssueGroup.class);
    }

    private List<IssueGroup> filterSeverities(List<IssueGroup> page, List<Severity> severities) {
        if (severities == null || severities.isEmpty()) {
            return page;
        }
        return page.stream()
            .filter(issue -> issue.getSeverity() != null && severities.stream()
                .anyMatch(s -> s.name().equalsIgnoreCase(issue.getSeverity())))
            .toList();
    }

    private Map<String, Object> baseQuery(Long codeRepoId, Long containerRepoId, Long teamId, String issueType, String status) {
        var query = new LinkedHashMap<String, Object>();
        if (codeRepoId != null) {
            query.put("filter_code_repo_id", codeRepoId);
        }
        if (containerRepoId != null) {
            query.put("filter_container_repo_id", containerRepoId);
        }
        if (teamId != null) {
            query.put("filter_team_id", teamId);
        }
        if (issueType != null && !issueType.isBlank()) {
            query.put("filter_issue_type", issueType);
        }
        if (status != null && !status.isBlank()) {
            query.put("filter_status", status);
        }
        return query;
    }

    /** Unchecked short-circuit signal used only by {@link #fetchOne}; stack trace suppressed since it carries no error. */
    private static final class FoundSignal extends RuntimeException {
        final transient IssueGroup item;

        FoundSignal(IssueGroup item) {
            super(null, null, false, false);
            this.item = item;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of issue groups fetched")
        private final Long size;

        @Schema(title = "Fetched issue groups", description = "Populated only when `fetchType=FETCH`.")
        private final List<IssueGroup> rows;

        @Schema(title = "First fetched issue group", description = "Populated only when `fetchType=FETCH_ONE`.")
        private final IssueGroup row;

        @Schema(title = "Stored data URI", description = "Populated only when `fetchType=STORE`.")
        private final URI uri;
    }
}
