package io.kestra.plugin.aikido.issues;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.plugin.aikido.AbstractAikidoTrigger;
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
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow on a new Aikido issue",
    description = """
        Polls `/open-issue-groups` at the configured interval, filtered client-side to issue groups whose \
        `severity_score` reaches `severityThreshold`, and fires one execution per newly discovered issue group \
        (oldest first, one per poll cycle) so no issue is skipped even during a burst. Since Aikido sorts this \
        endpoint by priority rather than detection time, every poll walks every open issue group page to find new \
        ones. The trigger tracks the `first_detected_at` of the newest delivered issue group in namespace KV to \
        avoid re-firing; on the first poll, only the current baseline is recorded — no execution is fired — to \
        avoid flooding on initial activation. An issue group whose severity is later re-scored upward, or that is \
        re-opened after being snoozed, is not re-delivered if its `first_detected_at` is older than the recorded \
        baseline.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "React to new critical issues via polling trigger",
            full = true,
            code = """
                id: aikido_critical_issue_alert
                namespace: company.security

                triggers:
                  - id: on_new_critical_issue
                    type: io.kestra.plugin.aikido.issues.Trigger
                    clientId: "{{ secret('AIKIDO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('AIKIDO_CLIENT_SECRET') }}"
                    severityThreshold: 80
                    interval: PT5M

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "Critical issue detected: {{ trigger.issueType }} — severity {{ trigger.severityScore }}"
                """
        )
    }
)
public class Trigger extends AbstractAikidoTrigger implements TriggerOutput<Trigger.Output> {
    private static final int PAGE_SIZE = 100;

    @Schema(
        title = "Severity threshold",
        description = "Minimum `severity_score` (0-100, Aikido's own numeric severity scale — not the `Severity` enum used elsewhere in this plugin) an issue group must reach to fire an execution. Defaults to `0` (any severity fires)."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(0) @Max(100) Integer> severityThreshold = Property.ofValue(0);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();
        var rThreshold = runContext.render(severityThreshold).as(Integer.class).orElse(0);

        var kv = runContext.namespaceKv(context.getNamespace());
        var key = watermarkKey(context.getFlowId(), getId());
        var watermark = readWatermark(kv, key);

        try (var client = client(runContext)) {
            var matching = fetchMatchingIssues(client, rThreshold);

            if (watermark == null) {
                seedBaseline(kv, key, matching, logger);
                return Optional.empty();
            }

            var candidates = matching.stream()
                .filter(issue -> issue.getFirstDetectedAt() != null && issue.getId() != null)
                .filter(issue -> issue.getFirstDetectedAt() >= watermark.getTimestamp())
                .filter(issue -> !isAlreadyDelivered(issue.getFirstDetectedAt(), issue.getId(), watermark))
                .sorted(Comparator.comparing(IssueGroup::getFirstDetectedAt))
                .toList();

            if (candidates.isEmpty()) {
                return Optional.empty();
            }

            var target = candidates.getFirst();
            logger.info("New Aikido issue group above severity threshold {}: id={}, type={}", rThreshold, target.getId(), target.getType());

            var output = Output.builder()
                .issueGroupId(target.getId())
                .issueType(target.getType())
                .severityScore(target.getSeverityScore())
                .severity(target.getSeverity())
                .title(target.getTitle())
                .firstDetectedAt(target.getFirstDetectedAt())
                .build();
            var execution = TriggerService.generateExecution(this, conditionContext, context, output);

            var newTimestamp = maxTimestamp(watermark.getTimestamp(), target.getFirstDetectedAt());
            persistWatermark(kv, key, AbstractAikidoTrigger.Watermark.builder()
                .timestamp(newTimestamp)
                .boundaryIds(nextBoundaryIds(newTimestamp, target.getId(), watermark))
                .build(), logger);

            return Optional.of(execution);
        }
    }

    /** Fetching only the newest baseline is still a full walk (this endpoint has no time-based filter/sort), so the same page-walking logic seeds the baseline as evaluates every subsequent poll. */
    private void seedBaseline(KVStore kv, String key, List<IssueGroup> matching, Logger logger) {
        var withTimestamp = matching.stream().filter(i -> i.getFirstDetectedAt() != null && i.getId() != null).toList();
        if (withTimestamp.isEmpty()) {
            logger.info("First poll: no Aikido issue groups above the severity threshold yet, seeding empty baseline.");
            persistWatermark(kv, key, AbstractAikidoTrigger.Watermark.builder().timestamp(0L).boundaryIds(Set.of()).build(), logger);
            return;
        }
        var maxTimestamp = withTimestamp.stream().map(IssueGroup::getFirstDetectedAt).max(Long::compareTo).orElse(0L);
        var boundaryIds = withTimestamp.stream()
            .filter(i -> i.getFirstDetectedAt().equals(maxTimestamp))
            .map(IssueGroup::getId)
            .collect(Collectors.toSet());
        logger.info("First poll: seeding Aikido issue trigger baseline at first_detected_at={}.", maxTimestamp);
        persistWatermark(kv, key, AbstractAikidoTrigger.Watermark.builder().timestamp(maxTimestamp).boundaryIds(boundaryIds).build(), logger);
    }

    private List<IssueGroup> fetchMatchingIssues(AikidoClient client, int threshold) throws Exception {
        var all = new ArrayList<IssueGroup>();
        AikidoPagination.walk(
            PAGE_SIZE,
            page -> client.getArray("/open-issue-groups", Map.of("page", page, "per_page", PAGE_SIZE), "issues:read", IssueGroup.class),
            all::addAll
        );
        return all.stream().filter(i -> i.getSeverityScore() != null && i.getSeverityScore() >= threshold).toList();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Issue group ID")
        private final Long issueGroupId;

        @Schema(title = "Issue type", description = "For example `open_source`, `leaked_secret`, `sast`, or `iac`.")
        private final String issueType;

        @Schema(title = "Numeric severity, from 1 (lowest) to 100 (highest)")
        private final Integer severityScore;

        @Schema(title = "Human-friendly severity", description = "One of `critical`, `high`, `medium`, or `low`.")
        private final String severity;

        @Schema(title = "Issue title")
        private final String title;

        @Schema(title = "Unix timestamp (seconds) this issue group was first detected")
        private final Long firstDetectedAt;
    }
}
