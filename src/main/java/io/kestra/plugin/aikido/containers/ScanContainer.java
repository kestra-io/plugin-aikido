package io.kestra.plugin.aikido.containers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.aikido.AbstractScanTask;
import io.kestra.plugin.aikido.AikidoApiException;
import io.kestra.plugin.aikido.AikidoClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Scan an Aikido container repository",
    description = "Triggers a rescan of a container image. The API call itself is fire-and-forget (no scan id); set `waitForCompletion` to poll the container's `last_scanned_at` timestamp until the scan finishes."
)
@Plugin(
    examples = {
        @Example(
            title = "Rescan a container image after a new build",
            full = true,
            code = """
                id: aikido_scan_container
                namespace: company.security

                inputs:
                  - id: container_id
                    type: STRING

                tasks:
                  - id: scan
                    type: io.kestra.plugin.aikido.containers.ScanContainer
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    containerId: "{{ inputs.container_id }}"
                    waitForCompletion: true
                """
        )
    }
)
public class ScanContainer extends AbstractScanTask implements RunnableTask<ScanContainer.Output> {
    @Schema(title = "Container repository ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> containerId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rContainerId = runContext.render(containerId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("containerId is required to scan an Aikido container repository."));
        var rWaitForCompletion = runContext.render(waitForCompletion).as(Boolean.class).orElse(false);
        var rPollInterval = runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(10));
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));

        var logger = runContext.logger();
        logger.info("Triggering Aikido scan for container repository '{}'", rContainerId);

        try (var client = client(runContext)) {
            Long baseline = null;
            if (rWaitForCompletion) {
                baseline = detail(client, rContainerId).getLastScannedAt();
            }

            ScanResponse response;
            try {
                response = client.post("/containers/" + rContainerId + "/scan", null, null, "containers:write", ScanResponse.class);
            } catch (AikidoApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("must be active before it can be scanned")) {
                    throw new IllegalStateException("Aikido container '" + rContainerId + "' must be active before it can be scanned — activate it in the Aikido console first.", e);
                }
                throw e;
            }
            var triggered = response != null && response.getSuccess() != null && response.getSuccess() == 1;

            if (!rWaitForCompletion) {
                return Output.builder().triggered(triggered).completed(false).build();
            }

            waitForScanCompletion(runContext, baseline, rPollInterval, rMaxDuration, rContainerId, () -> detail(client, rContainerId).getLastScannedAt());
            return Output.builder().triggered(triggered).completed(true).build();
        }
    }

    private ContainerDetail detail(AikidoClient client, String containerId) throws Exception {
        return client.get("/containers/" + containerId, null, "repositories:read", ContainerDetail.class);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ContainerDetail {
        @JsonProperty("last_scanned_at")
        private Long lastScannedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ScanResponse {
        private Integer success;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Whether the scan was successfully triggered")
        private final Boolean triggered;

        @Schema(title = "Whether the task waited for and observed scan completion", description = "Always `false` when `waitForCompletion` is `false`.")
        private final Boolean completed;
    }
}
