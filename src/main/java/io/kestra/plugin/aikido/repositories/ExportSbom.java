package io.kestra.plugin.aikido.repositories;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.aikido.AbstractAikidoTask;
import io.kestra.plugin.aikido.AikidoApiException;
import io.kestra.plugin.aikido.SbomFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Export the SBOM/license report of an Aikido code repository",
    description = "Downloads the license and package overview for a code repository and stores it to internal storage. Fails with an actionable message if the repository has no completed scan yet — run `Scan` first."
)
@Plugin(
    examples = {
        @Example(
            title = "Export a repository's SBOM in SPDX format",
            full = true,
            code = """
                id: export_repository_sbom
                namespace: company.security

                inputs:
                  - id: repo_id
                    type: STRING

                tasks:
                  - id: export_sbom
                    type: io.kestra.plugin.aikido.repositories.ExportSbom
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    repositoryId: "{{ inputs.repo_id }}"
                    format: SBOM_SPDX
                """
        )
    }
)
public class ExportSbom extends AbstractAikidoTask implements RunnableTask<ExportSbom.Output> {
    @Schema(title = "Code repository ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> repositoryId;

    @Schema(title = "Export format", description = "`CSV` for a flat license/package table, `SBOM` for CycloneDX, `SBOM_SPDX` for SPDX. Defaults to `CSV`.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<SbomFormat> format = Property.ofValue(SbomFormat.CSV);

    @Schema(title = "Dependency scope to include", description = "Defaults to `ALL`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SbomScope> scope = Property.ofValue(SbomScope.ALL);

    @Schema(title = "Filter by path", description = "Only include packages found under this path.")
    @PluginProperty(group = "processing")
    private Property<String> location;

    @Schema(title = "Include the reason a package was flagged as risky", description = "Defaults to `false`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> includeRiskReason = Property.ofValue(false);

    @Schema(title = "Include VEX (Vulnerability Exploitability eXchange) statements", description = "Defaults to `false`.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> includeVex = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rRepositoryId = runContext.render(repositoryId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("repositoryId is required to export an Aikido repository SBOM."));
        var rFormat = runContext.render(format).as(SbomFormat.class).orElse(SbomFormat.CSV);
        var rScope = runContext.render(scope).as(SbomScope.class).orElse(SbomScope.ALL);
        var rLocation = runContext.render(location).as(String.class).orElse(null);
        var rIncludeRiskReason = runContext.render(includeRiskReason).as(Boolean.class).orElse(false);
        var rIncludeVex = runContext.render(includeVex).as(Boolean.class).orElse(false);

        var query = new LinkedHashMap<String, Object>();
        query.put("format", rFormat.name().toLowerCase());
        query.put("scope", rScope.name().toLowerCase());
        query.put("include_risk_reason", rIncludeRiskReason ? 1 : 0);
        query.put("include_vex", rIncludeVex ? 1 : 0);
        if (rLocation != null && !rLocation.isBlank()) {
            query.put("location", rLocation);
        }

        runContext.logger().info("Exporting Aikido SBOM for code repository '{}' as {}", rRepositoryId, rFormat);

        try (var client = client(runContext)) {
            var extension = rFormat == SbomFormat.CSV ? ".csv" : ".json";
            var tempFile = runContext.workingDir().createTempFile(extension).toFile();
            try {
                client.getStream("/repositories/code/" + rRepositoryId + "/licenses/export", query, "repositories:read", inputStream -> {
                    try (var out = new FileOutputStream(tempFile)) {
                        inputStream.transferTo(out);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (AikidoApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                    throw new IllegalStateException("No SBOM available for Aikido repository '" + rRepositoryId + "' — it has likely not completed a scan yet. Run Scan first.", e);
                }
                throw e;
            }
            var size = tempFile.length();
            var uri = runContext.storage().putFile(tempFile, "aikido-repository-sbom" + extension);
            return Output.builder().uri(uri).size(size).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Stored SBOM/license report URI")
        private final URI uri;

        @Schema(title = "Size of the stored file, in bytes")
        private final Long size;
    }
}
