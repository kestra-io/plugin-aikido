package io.kestra.plugin.aikido.clouds;

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
    title = "List Aikido cloud assets",
    description = """
        Lists cloud assets discovered across every connected cloud environment (or a single one via `cloudId`) and \
        pages through every matching result. `fetchType=STORE` is recommended for large result sets: it streams \
        results to internal storage instead of holding them all in memory or in the execution output.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Store every discovered EC2 instance for a given cloud",
            full = true,
            code = """
                id: list_aikido_cloud_assets
                namespace: company.security

                inputs:
                  - id: cloud_id
                    type: STRING

                tasks:
                  - id: list_assets
                    type: io.kestra.plugin.aikido.clouds.ListAssets
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    cloudId: "{{ inputs.cloud_id }}"
                    assetType: "AWS::EC2::Instance"
                    fetchType: STORE
                """
        )
    }
)
public class ListAssets extends AbstractAikidoTask implements RunnableTask<ListAssets.Output> {
    @Schema(title = "Filter by connected cloud ID")
    @PluginProperty(group = "processing")
    private Property<Long> cloudId;

    @Schema(title = "Filter by provider-specific asset type", description = "For example `AWS::EC2::Instance`.")
    @PluginProperty(group = "processing")
    private Property<String> assetType;

    @Schema(title = "Filter by region")
    @PluginProperty(group = "processing")
    private Property<String> region;

    @Schema(title = "Filter by cloud provider", description = "For example `aws`, `azure`, or `gcp`.")
    @PluginProperty(group = "processing")
    private Property<String> provider;

    @Schema(title = "Free-text search across asset name and identifiers")
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Schema(title = "Include full provider metadata on each asset", description = "Defaults to `false`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> includeMetadata = Property.ofValue(false);

    @Schema(title = "Page size", description = "Internal pagination page size, from 10 to 100. Defaults to `20`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(10) @Max(100) Integer> pageSize = Property.ofValue(20);

    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` outputs the first matching asset, `FETCH` outputs every matching asset, `STORE` streams every matching asset to internal storage and returns a URI. Defaults to `FETCH`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rCloudId = runContext.render(cloudId).as(Long.class).orElse(null);
        var rAssetType = runContext.render(assetType).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rProvider = runContext.render(provider).as(String.class).orElse(null);
        var rSearch = runContext.render(search).as(String.class).orElse(null);
        var rIncludeMetadata = runContext.render(includeMetadata).as(Boolean.class).orElse(false);
        var rPageSize = runContext.render(pageSize).as(Integer.class).orElse(20);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        var query = new LinkedHashMap<String, Object>();
        if (rCloudId != null) {
            query.put("cloud_id", rCloudId);
        }
        if (rAssetType != null && !rAssetType.isBlank()) {
            query.put("asset_type", rAssetType);
        }
        if (rRegion != null && !rRegion.isBlank()) {
            query.put("region", rRegion);
        }
        if (rProvider != null && !rProvider.isBlank()) {
            query.put("provider", rProvider);
        }
        if (rSearch != null && !rSearch.isBlank()) {
            query.put("search", rSearch);
        }
        query.put("include_metadata", rIncludeMetadata);

        runContext.logger().info("Listing Aikido cloud assets");

        try (var client = client(runContext)) {
            return switch (rFetchType) {
                case FETCH_ONE -> {
                    var page = fetchPage(client, 0, rPageSize, query);
                    yield page.isEmpty() ? Output.builder().size(0L).build() : Output.builder().size(1L).row(page.getFirst()).build();
                }
                case STORE -> store(runContext, client, query, rPageSize);
                case FETCH -> {
                    var all = new ArrayList<CloudAsset>();
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

    /** Unlike every other list endpoint in this plugin, `/clouds/assets` is `GET` (the issue text says `POST` — verified against the OpenAPI spec, it is `GET`), wraps its items in `{assets, totalCount}`, and paginates with `limit` instead of `per_page`. */
    private List<CloudAsset> fetchPage(AikidoClient client, int page, int pageSize, Map<String, Object> query) throws Exception {
        var withPage = new LinkedHashMap<>(query);
        withPage.put("page", page);
        withPage.put("limit", pageSize);
        var response = client.get("/clouds/assets", withPage, "clouds:read", CloudAssetListResponse.class);
        return response == null ? List.of() : response.assetsOrEmpty();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of assets fetched")
        private final Long size;

        @Schema(title = "Fetched assets", description = "Populated only when `fetchType=FETCH`.")
        private final List<CloudAsset> rows;

        @Schema(title = "First fetched asset", description = "Populated only when `fetchType=FETCH_ONE`.")
        private final CloudAsset row;

        @Schema(title = "Stored data URI", description = "Populated only when `fetchType=STORE`.")
        private final URI uri;
    }
}
