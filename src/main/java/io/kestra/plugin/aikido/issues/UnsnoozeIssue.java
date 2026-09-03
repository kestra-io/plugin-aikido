package io.kestra.plugin.aikido.issues;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Unsnooze an Aikido issue group",
    description = "Cancels an active snooze on an issue group, making it visible in the open issues feed again."
)
@Plugin(
    examples = {
        @Example(
            title = "Unsnooze an issue group",
            full = true,
            code = """
                id: unsnooze_issue
                namespace: company.security

                inputs:
                  - id: issue_group_id
                    type: STRING

                tasks:
                  - id: unsnooze
                    type: io.kestra.plugin.aikido.issues.UnsnoozeIssue
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    issueGroupId: "{{ inputs.issue_group_id }}"
                """
        )
    }
)
public class UnsnoozeIssue extends AbstractAikidoTask implements RunnableTask<UnsnoozeIssue.Output> {
    @Schema(title = "Issue group ID")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> issueGroupId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rIssueGroupId = runContext.render(issueGroupId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("issueGroupId is required to unsnooze an Aikido issue."));

        runContext.logger().info("Unsnoozing Aikido issue group '{}'", rIssueGroupId);

        try (var client = client(runContext)) {
            var response = client.put("/issues/groups/" + rIssueGroupId + "/unsnooze", null, "issues:write", UnsnoozeResponse.class);
            if (response == null) {
                throw new IllegalStateException("No issue group found with ID '" + rIssueGroupId + "' — verify the ID or that the API client has access to it.");
            }
            return Output.builder().status(response.getStatus()).build();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class UnsnoozeResponse {
        private String status;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Resulting status of the issue group")
        private final String status;
    }
}
