package io.kestra.plugin.aikido.domains;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.aikido.AbstractScanTask;
import io.kestra.plugin.aikido.AikidoClient;
import io.kestra.plugin.aikido.AikidoPagination;
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
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Scan an Aikido domain",
    description = """
        Triggers a DAST scan on a connected domain. The API call itself is fire-and-forget (no scan id); set \
        `waitForCompletion` to poll the domain's `last_scanned_at` timestamp (read via `ListDomains`, since Aikido \
        has no single-domain detail endpoint) until the scan finishes.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a DAST scan on a domain",
            full = true,
            code = """
                id: aikido_scan_domain
                namespace: company.security

                inputs:
                  - id: domain_id
                    type: STRING

                tasks:
                  - id: scan
                    type: io.kestra.plugin.aikido.domains.ScanDomain
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    domainId: "{{ inputs.domain_id }}"
                    waitForCompletion: true
                """
        )
    }
)
public class ScanDomain extends AbstractScanTask implements RunnableTask<ScanDomain.Output> {
    private static final int PAGE_SIZE = 100;

    @Schema(title = "Domain ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> domainId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rDomainId = runContext.render(domainId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("domainId is required to scan an Aikido domain."));
        var rWaitForCompletion = runContext.render(waitForCompletion).as(Boolean.class).orElse(false);
        var rPollInterval = runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(10));
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var domainIdLong = Long.parseLong(rDomainId);

        var logger = runContext.logger();
        logger.info("Triggering Aikido scan for domain '{}'", rDomainId);

        try (var client = client(runContext)) {
            Long baseline = null;
            if (rWaitForCompletion) {
                baseline = findLastScannedAt(client, domainIdLong);
            }

            var response = client.post("/domains/scan", null, Map.of("domain_id", domainIdLong), "domains:write", ScanResponse.class);
            var triggered = response != null && response.getSuccess() != null && response.getSuccess() == 1;

            if (!rWaitForCompletion) {
                return Output.builder().triggered(triggered).completed(false).build();
            }

            waitForScanCompletion(runContext, baseline, rPollInterval, rMaxDuration, rDomainId, () -> findLastScannedAt(client, domainIdLong));
            return Output.builder().triggered(triggered).completed(true).build();
        }
    }

    /** Aikido has no single-domain detail endpoint, so completion polling walks `/domains` looking for a matching ID. */
    private Long findLastScannedAt(AikidoClient client, long domainId) throws Exception {
        try {
            AikidoPagination.walk(PAGE_SIZE, page -> client.getArray("/domains", Map.of("page", page, "per_page", PAGE_SIZE), "domains:read", Domain.class), page -> {
                page.stream()
                    .filter(d -> d.getId() != null && d.getId() == domainId)
                    .findFirst()
                    .ifPresent(d -> {
                        throw new FoundSignal(d.getLastScannedAt());
                    });
            });
        } catch (FoundSignal signal) {
            return signal.lastScannedAt;
        }
        throw new IllegalStateException("Aikido domain '" + domainId + "' was not found while polling for scan completion — it may have been removed.");
    }

    private static final class FoundSignal extends RuntimeException {
        final transient Long lastScannedAt;

        FoundSignal(Long lastScannedAt) {
            super(null, null, false, false);
            this.lastScannedAt = lastScannedAt;
        }
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
