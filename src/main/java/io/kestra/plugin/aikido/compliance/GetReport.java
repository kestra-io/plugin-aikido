package io.kestra.plugin.aikido.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.aikido.AbstractAikidoTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get an Aikido compliance framework overview",
    description = "Fetches the compliance overview for a given framework: the rule-by-rule pass/fail breakdown and the overall compliant-rule count."
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch the SOC 2 compliance overview",
            full = true,
            code = """
                id: get_soc2_overview
                namespace: company.security

                tasks:
                  - id: compliance
                    type: io.kestra.plugin.aikido.compliance.GetReport
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    framework: SOC2
                """
        )
    }
)
public class GetReport extends AbstractAikidoTask implements RunnableTask<GetReport.Output> {
    @Schema(title = "Compliance framework")
    @NotNull
    @PluginProperty(group = "main")
    private Property<ComplianceFramework> framework;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFramework = runContext.render(framework).as(ComplianceFramework.class)
            .orElseThrow(() -> new IllegalArgumentException("framework is required to fetch an Aikido compliance overview."));

        runContext.logger().info("Fetching Aikido {} compliance overview", rFramework);

        try (var client = client(runContext)) {
            var overview = client.get("/report/" + rFramework.pathSegment() + "/overview", null, "reports:read", ComplianceOverviewResponse.class);
            if (overview == null) {
                throw new IllegalStateException("Aikido returned no compliance data for framework '" + rFramework + "' — verify the account has this framework enabled.");
            }
            return Output.builder()
                .overview(overview.getOverview())
                .totalComplyingRuleCount(overview.getTotalComplyingRuleCount())
                .totalRuleCount(overview.getTotalRuleCount())
                .build();
        }
    }

    /**
     * {@code GET /report/{framework}/overview} returns this single object — Aikido's OpenAPI spec declares the
     * response as an array, but the live API (verified on soc2/iso/nis2) returns the object directly.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ComplianceOverviewResponse {
        private Map<String, Object> overview;
        @JsonProperty("total_complying_rule_count")
        private Integer totalComplyingRuleCount;
        @JsonProperty("total_rule_count")
        private Integer totalRuleCount;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The rule-by-rule compliance overview", description = "Structure is framework-specific; keys are Aikido control categories, values contain per-measure pass/fail results.")
        private final Map<String, Object> overview;

        @Schema(title = "Number of rules currently compliant")
        private final Integer totalComplyingRuleCount;

        @Schema(title = "Total number of rules evaluated")
        private final Integer totalRuleCount;
    }
}
