package io.kestra.plugin.aikido.repositories;

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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Aikido code repositories",
    description = "Lists code repositories connected to Aikido and pages through every matching result. `fetchType=STORE` is recommended for large result sets: it streams results to internal storage instead of holding them all in memory or in the execution output."
)
@Plugin(
    examples = {
        @Example(
            title = "List every active code repository",
            full = true,
            code = """
                id: list_aikido_repositories
                namespace: company.security

                tasks:
                  - id: list_repositories
                    type: io.kestra.plugin.aikido.repositories.ListRepositories
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class ListRepositories extends AbstractAikidoTask implements RunnableTask<ListRepositories.Output> {
    @Schema(title = "Include inactive repositories", description = "Defaults to `false`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> includeInactive = Property.ofValue(false);

    @Schema(title = "Filter by repository name")
    @PluginProperty(group = "processing")
    private Property<String> filterName;

    @Schema(title = "Filter by branch")
    @PluginProperty(group = "processing")
    private Property<String> filterBranch;

    @Schema(title = "Page size", description = "Internal pagination page size, from 10 to 200. Defaults to `20`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(10) @Max(200) Integer> pageSize = Property.ofValue(20);

    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` outputs the first matching repository, `FETCH` outputs every matching repository, `STORE` streams every matching repository to internal storage and returns a URI. Defaults to `FETCH`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rIncludeInactive = runContext.render(includeInactive).as(Boolean.class).orElse(false);
        var rFilterName = runContext.render(filterName).as(String.class).orElse(null);
        var rFilterBranch = runContext.render(filterBranch).as(String.class).orElse(null);
        var rPageSize = runContext.render(pageSize).as(Integer.class).orElse(20);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        var query = new LinkedHashMap<String, Object>();
        query.put("include_inactive", rIncludeInactive);
        if (rFilterName != null && !rFilterName.isBlank()) {
            query.put("filter_name", rFilterName);
        }
        if (rFilterBranch != null && !rFilterBranch.isBlank()) {
            query.put("filter_branch", rFilterBranch);
        }

        runContext.logger().info("Listing Aikido code repositories");

        try (var client = client(runContext)) {
            return switch (rFetchType) {
                case FETCH_ONE -> {
                    var page = fetchPage(client, 0, rPageSize, query);
                    yield page.isEmpty() ? Output.builder().size(0L).build() : Output.builder().size(1L).row(page.getFirst()).build();
                }
                case STORE -> store(runContext, client, query, rPageSize);
                case FETCH -> {
                    var all = new ArrayList<CodeRepository>();
                    var size = AikidoPagination.walk(rPageSize, page -> fetchPage(client, page, rPageSize, query), all::addAll);
                    yield Output.builder().size(size).rows(all).build();
                }
                case NONE -> {
                    var size = AikidoPagination.walk(rPageSize, page -> fetchPage(client, page, rPageSize, query), page -> { });
                    yield Output.builder().size(size).build();
                }
            };
        }
    }

    private Output store(RunContext runContext, AikidoClient client, Map<String, Object> query, int pageSize) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        long size;
        try (var writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            size = AikidoPagination.walk(pageSize, page -> fetchPage(client, page, pageSize, query), page -> {
                if (!page.isEmpty()) {
                    FileSerde.writeAll(writer, Flux.fromIterable(page)).block();
                }
            });
        }
        return Output.builder().size(size).uri(runContext.storage().putFile(tempFile)).build();
    }

    private List<CodeRepository> fetchPage(AikidoClient client, int page, int pageSize, Map<String, Object> query) throws Exception {
        var withPage = new LinkedHashMap<>(query);
        withPage.put("page", page);
        withPage.put("per_page", pageSize);
        return client.getArray("/repositories/code", withPage, "repositories:read", CodeRepository.class);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of repositories fetched")
        private final Long size;

        @Schema(title = "Fetched repositories", description = "Populated only when `fetchType=FETCH`.")
        private final List<CodeRepository> rows;

        @Schema(title = "First fetched repository", description = "Populated only when `fetchType=FETCH_ONE`.")
        private final CodeRepository row;

        @Schema(title = "Stored data URI", description = "Populated only when `fetchType=STORE`.")
        private final URI uri;
    }
}
