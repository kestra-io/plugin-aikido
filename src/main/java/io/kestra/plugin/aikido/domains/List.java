package io.kestra.plugin.aikido.domains;

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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Aikido domains",
    description = "Lists domains connected to Aikido's surface/DAST monitoring and pages through every matching result."
)
@Plugin(
    examples = {
        @Example(
            title = "List every monitored domain",
            full = true,
            code = """
                id: list_aikido_domains
                namespace: company.security

                tasks:
                  - id: list_domains
                    type: io.kestra.plugin.aikido.domains.List
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractAikidoTask implements RunnableTask<List.Output> {
    @Schema(title = "Page size", description = "Internal pagination page size, from 10 to 100. Defaults to `20`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(10) @Max(100) Integer> pageSize = Property.ofValue(20);

    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` outputs the first domain, `FETCH` outputs every domain, `STORE` streams every domain to internal storage and returns a URI. Defaults to `FETCH`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rPageSize = runContext.render(pageSize).as(Integer.class).orElse(20);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        runContext.logger().info("Listing Aikido domains");

        try (var client = client(runContext)) {
            return switch (rFetchType) {
                case FETCH_ONE -> {
                    var page = fetchPage(client, 0, rPageSize);
                    yield page.isEmpty() ? Output.builder().size(0L).build() : Output.builder().size(1L).row(page.getFirst()).build();
                }
                case STORE -> store(runContext, client, rPageSize);
                case FETCH -> {
                    var all = new ArrayList<Domain>();
                    var size = AikidoPagination.walk(rPageSize, page -> fetchPage(client, page, rPageSize), all::addAll);
                    yield Output.builder().size(size).rows(all).build();
                }
                case NONE -> {
                    var size = AikidoPagination.walk(rPageSize, page -> fetchPage(client, page, rPageSize), page -> { });
                    yield Output.builder().size(size).build();
                }
            };
        }
    }

    private Output store(RunContext runContext, AikidoClient client, int pageSize) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        long size;
        try (var writer = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            size = AikidoPagination.walk(pageSize, page -> fetchPage(client, page, pageSize), page -> {
                if (!page.isEmpty()) {
                    FileSerde.writeAll(writer, Flux.fromIterable(page)).block();
                }
            });
        }
        return Output.builder().size(size).uri(runContext.storage().putFile(tempFile)).build();
    }

    private java.util.List<Domain> fetchPage(AikidoClient client, int page, int pageSize) throws Exception {
        var query = new LinkedHashMap<String, Object>();
        query.put("page", page);
        query.put("per_page", pageSize);
        return client.getArray("/domains", query, "domains:read", Domain.class);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of domains fetched")
        private final Long size;

        @Schema(title = "Fetched domains", description = "Populated only when `fetchType=FETCH`.")
        private final java.util.List<Domain> rows;

        @Schema(title = "First fetched domain", description = "Populated only when `fetchType=FETCH_ONE`.")
        private final Domain row;

        @Schema(title = "Stored data URI", description = "Populated only when `fetchType=STORE`.")
        private final URI uri;
    }
}
