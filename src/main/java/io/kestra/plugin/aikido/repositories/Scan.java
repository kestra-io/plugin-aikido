package io.kestra.plugin.aikido.repositories;

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
import java.util.LinkedHashMap;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Scan an Aikido code repository",
    description = "Triggers a rescan of a code repository. The API call itself is fire-and-forget (no scan id); set `waitForCompletion` to poll the repository's `last_scanned_at` timestamp until the scan finishes."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a SAST + secrets scan on a repository after a deployment",
            full = true,
            code = """
                id: aikido_scan_on_deploy
                namespace: company.security

                inputs:
                  - id: repo_id
                    type: STRING
                    description: Aikido code repository ID to scan

                tasks:
                  - id: scan
                    type: io.kestra.plugin.aikido.repositories.Scan
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    repositoryId: "{{ inputs.repo_id }}"
                    sast: true
                    secrets: true
                    iac: true
                    waitForCompletion: true

                  - id: log_result
                    type: io.kestra.plugin.core.log.Log
                    message: "Scan completed for repo {{ inputs.repo_id }} — status: {{ outputs.scan.completed }}"
                """
        )
    }
)
public class Scan extends AbstractScanTask implements RunnableTask<Scan.Output> {
    @Schema(title = "Code repository ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> repositoryId;

    @Schema(title = "Run a SAST scan", description = "Defaults to `true`.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> sast = Property.ofValue(true);

    @Schema(title = "Run an IaC scan", description = "Defaults to `true`.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> iac = Property.ofValue(true);

    @Schema(title = "Run a secrets scan", description = "Defaults to `true`.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> secrets = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rRepositoryId = runContext.render(repositoryId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("repositoryId is required to scan an Aikido code repository."));
        var rSast = runContext.render(sast).as(Boolean.class).orElse(true);
        var rIac = runContext.render(iac).as(Boolean.class).orElse(true);
        var rSecrets = runContext.render(secrets).as(Boolean.class).orElse(true);
        var rWaitForCompletion = runContext.render(waitForCompletion).as(Boolean.class).orElse(false);
        var rPollInterval = runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(10));
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));

        var logger = runContext.logger();
        logger.info("Triggering Aikido scan for code repository '{}' (sast={}, iac={}, secrets={})", rRepositoryId, rSast, rIac, rSecrets);

        try (var client = client(runContext)) {
            Long baseline = null;
            if (rWaitForCompletion) {
                baseline = detail(client, rRepositoryId).getLastScannedAt();
            }

            var query = new LinkedHashMap<String, Object>();
            query.put("include_sast_scan", rSast);
            query.put("include_iac_scan", rIac);
            query.put("include_secrets_scan", rSecrets);

            int status;
            try {
                status = client.post("/repositories/code/" + rRepositoryId + "/scan", query, null, "repositories:write").getStatus().getCode();
            } catch (AikidoApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("must be active before it can be scanned")) {
                    throw new IllegalStateException("Aikido repository '" + rRepositoryId + "' must be active before it can be scanned — activate it in the Aikido console first.", e);
                }
                throw e;
            }

            var triggered = status == 204 || status == 200;

            if (!rWaitForCompletion) {
                return Output.builder().triggered(triggered).completed(false).build();
            }

            waitForScanCompletion(runContext, baseline, rPollInterval, rMaxDuration, rRepositoryId, () -> detail(client, rRepositoryId).getLastScannedAt());
            return Output.builder().triggered(triggered).completed(true).build();
        }
    }

    private RepositoryDetail detail(AikidoClient client, String repositoryId) throws Exception {
        return client.get("/repositories/code/" + repositoryId, null, "repositories:read", RepositoryDetail.class);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RepositoryDetail {
        @JsonProperty("last_scanned_at")
        private Long lastScannedAt;
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
