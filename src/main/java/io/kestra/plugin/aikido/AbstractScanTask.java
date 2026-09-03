package io.kestra.plugin.aikido;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared `waitForCompletion` polling for the repository, container, and domain scan tasks.
 *
 * <p>Aikido's scan-trigger endpoints are fire-and-forget: they return no scan id and there is no
 * scan-status endpoint to poll. Every scannable resource does expose a `last_scanned_at` timestamp on
 * its detail/list endpoint, so waiting for completion is approximated by reading that timestamp
 * before triggering the scan (the baseline) and polling until it advances past the baseline. This
 * observes "a scan finished" on the resource, not necessarily "the scan this task started finished" —
 * a concurrent scan on the same resource can also satisfy it.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractScanTask extends AbstractAikidoTask {
    @Schema(
        title = "Wait for scan completion",
        description = """
            When true, polls the resource's `last_scanned_at` timestamp after triggering the scan until it \
            advances past its pre-scan value, then returns. Caveat: Aikido's scan-trigger endpoints are \
            fire-and-forget with no scan id and no status endpoint, so this observes "a scan finished" on the \
            resource, not necessarily "the scan this task started finished" — a concurrent scan on the same \
            resource can also satisfy it. Defaults to `false`, so the task returns immediately after triggering.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Boolean> waitForCompletion = Property.ofValue(false);

    @Schema(
        title = "Poll interval",
        description = "How often to re-check `last_scanned_at` while waiting for completion. Only used when `waitForCompletion` is `true`. Defaults to `PT10S`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(10));

    @Schema(
        title = "Maximum wait duration",
        description = "Maximum time to wait for the scan to complete before failing. Only used when `waitForCompletion` is `true`. Defaults to `PT1H`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    /**
     * Blocks the worker thread until {@code supplier} reports a `last_scanned_at` value strictly greater
     * than {@code baseline}, or throws once {@code maxDuration} elapses.
     */
    protected void waitForScanCompletion(RunContext runContext, Long baseline, Duration rPollInterval, Duration rMaxDuration, String resourceId, LastScannedAtSupplier supplier) throws Exception {
        var logger = runContext.logger();
        var deadline = Instant.now().plus(rMaxDuration);
        Long last = baseline;

        while (Instant.now().isBefore(deadline)) {
            last = supplier.get();
            if (last != null && (baseline == null || last > baseline)) {
                logger.info("Aikido scan completed for resource '{}': last_scanned_at advanced to {}.", resourceId, last);
                return;
            }
            Thread.sleep(rPollInterval.toMillis());
        }

        throw new IllegalStateException(
            "Timed out after " + rMaxDuration + " waiting for the Aikido scan on resource '" + resourceId +
                "' to complete (last-seen last_scanned_at: " + last + "). The scan may still be running — check the Aikido console, or increase 'maxDuration'."
        );
    }

    @FunctionalInterface
    protected interface LastScannedAtSupplier {
        Long get() throws Exception;
    }
}
