package io.kestra.plugin.aikido.issues;

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

import java.time.Instant;
import java.util.LinkedHashMap;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Snooze an Aikido issue group",
    description = "Snoozes an issue group until a given point in time, optionally recording a reason. A snoozed issue group is excluded from the open issues feed until it re-opens."
)
@Plugin(
    examples = {
        @Example(
            title = "Snooze a false-positive issue for 30 days",
            full = true,
            code = """
                id: snooze_false_positive
                namespace: company.security

                inputs:
                  - id: issue_group_id
                    type: STRING

                tasks:
                  - id: snooze
                    type: io.kestra.plugin.aikido.issues.Snooze
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    issueGroupId: "{{ inputs.issue_group_id }}"
                    until: "2026-12-31T00:00:00Z"
                    reason: "False positive, confirmed by security team"
                """
        )
    }
)
public class Snooze extends AbstractAikidoTask implements RunnableTask<Snooze.Output> {
    @Schema(title = "Issue group ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> issueGroupId;

    @Schema(title = "Snooze until", description = "Point in time until which this issue group is snoozed. Must be in the future.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<Instant> until;

    @Schema(title = "Reason for snoozing")
    @PluginProperty(group = "main")
    private Property<String> reason;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rIssueGroupId = runContext.render(issueGroupId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("issueGroupId is required to snooze an Aikido issue."));
        var rUntil = runContext.render(until).as(Instant.class)
            .orElseThrow(() -> new IllegalArgumentException("until is required to snooze an Aikido issue."));
        if (rUntil.isBefore(Instant.now())) {
            throw new IllegalArgumentException("until (" + rUntil + ") is in the past — an Aikido issue can only be snoozed until a future point in time.");
        }
        var rReason = runContext.render(reason).as(String.class).orElse(null);

        var body = new LinkedHashMap<String, Object>();
        body.put("snooze_until", rUntil.getEpochSecond());
        if (rReason != null && !rReason.isBlank()) {
            body.put("reason", rReason);
        }

        runContext.logger().info("Snoozing Aikido issue group '{}' until {}", rIssueGroupId, rUntil);

        try (var client = client(runContext)) {
            var response = client.put("/issues/groups/" + rIssueGroupId + "/snooze", body, "issues:write", SnoozeResponse.class);
            if (response == null) {
                throw new IllegalStateException("No issue group found with ID '" + rIssueGroupId + "' — verify the ID or that the API client has access to it.");
            }
            return Output.builder()
                .success(response.getSuccess())
                .snoozedSingleIssuesAmount(response.getSnoozedSingleIssuesAmount())
                .build();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SnoozeResponse {
        private Boolean success;
        @JsonProperty("snoozed_single_issues_amount")
        private Integer snoozedSingleIssuesAmount;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Whether the snooze operation succeeded")
        private final Boolean success;

        @Schema(title = "Number of individual issues snoozed as part of this issue group")
        private final Integer snoozedSingleIssuesAmount;
    }
}
