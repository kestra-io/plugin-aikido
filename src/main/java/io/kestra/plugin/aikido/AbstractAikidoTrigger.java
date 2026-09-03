package io.kestra.plugin.aikido;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class for Aikido polling triggers: holds the connection properties (duplicated from
 * {@link AbstractAikidoTask} — a trigger cannot extend {@code Task}) and the watermark persistence
 * shared by every poller so a delivered item is never re-fired.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAikidoTrigger extends AbstractTrigger implements PollingTriggerInterface, AikidoConnectionInterface {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = JacksonMapper.ofJson();

    protected static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration HTTP_READ_IDLE_TIMEOUT = Duration.ofSeconds(30);

    @Schema(title = BASE_URL_TITLE, description = BASE_URL_DESCRIPTION)
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(AbstractAikidoTask.DEFAULT_BASE_URL);

    @Schema(title = CLIENT_ID_TITLE, description = CLIENT_ID_DESCRIPTION)
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> clientId;

    @Schema(title = CLIENT_SECRET_TITLE, description = CLIENT_SECRET_DESCRIPTION)
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> clientSecret;

    @Schema(
        title = "Polling interval",
        description = "How often to poll the Aikido API for new issues. Defaults to `PT5M` (every 5 minutes)."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Duration interval = Duration.ofMinutes(5);

    protected AikidoClient client(RunContext runContext) throws Exception {
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse(AbstractAikidoTask.DEFAULT_BASE_URL);
        var rClientId = runContext.render(clientId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clientId is required to authenticate with the Aikido API."));
        var rClientSecret = runContext.render(clientSecret).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clientSecret is required to authenticate with the Aikido API."));

        var httpClient = HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder()
                .timeout(TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(HTTP_CONNECT_TIMEOUT))
                    .readIdleTimeout(Property.ofValue(HTTP_READ_IDLE_TIMEOUT))
                    .build())
                .build())
            .build();
        return new AikidoClient(runContext, httpClient, rBaseUrl, rClientId, rClientSecret);
    }

    /**
     * Length-prefixes each segment so two distinct (flowId, triggerId) pairs whose concatenation would
     * otherwise collide (e.g. ("ab", "c") vs. ("a", "bc")) never share a KV key.
     */
    protected String watermarkKey(String flowId, String triggerId) {
        return "aikido_watermark_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId;
    }

    /**
     * A read failure here must never be treated as "no watermark yet" — that would silently reset the
     * trigger to first-poll and drop every issue accumulated since the last successful poll.
     */
    protected Watermark readWatermark(KVStore kv, String key) {
        try {
            return kv.getValue(key)
                .map(v -> parseWatermark(String.valueOf(v.value())))
                .orElse(null);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to read Aikido trigger watermark '" + key + "' from the namespace KV store — refusing to " +
                    "silently treat this as the first poll, which would re-seed the baseline and drop the backlog: " + e.getMessage(), e
            );
        }
    }

    /**
     * Persisted every time a new watermark is established, whether or not the poll fires an execution. A
     * write failure here must fail the poll loudly instead of returning an execution whose delivery was
     * never durably recorded — otherwise the next poll re-delivers the same issue.
     */
    protected void persistWatermark(KVStore kv, String key, Watermark watermark, Logger logger) {
        try {
            kv.put(key, new KVValueAndMetadata(new KVMetadata(null, (Instant) null), serializeWatermark(watermark, logger)));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to persist Aikido trigger watermark '" + key + "' — the next poll would otherwise re-deliver the same issue(s): " + e.getMessage(), e
            );
        }
    }

    private String serializeWatermark(Watermark watermark, Logger logger) {
        try {
            return MAPPER.writeValueAsString(watermark);
        } catch (Exception e) {
            logger.warn("Failed to serialize Aikido trigger watermark, falling back to timestamp-only tracking: {}", e.getMessage());
            return String.valueOf(watermark.getTimestamp());
        }
    }

    private Watermark parseWatermark(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Watermark.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Unparseable Aikido trigger watermark '" + raw + "' — refusing to silently treat this as the first " +
                    "poll, which would re-seed the baseline and drop the backlog: " + e.getMessage(), e
            );
        }
    }

    /** True when an issue group with `itemTimestamp`/`itemId` was already delivered by a previous poll. */
    protected boolean isAlreadyDelivered(Long itemTimestamp, Long itemId, Watermark watermark) {
        return itemTimestamp != null
            && itemTimestamp.equals(watermark.getTimestamp())
            && watermark.getBoundaryIds() != null
            && watermark.getBoundaryIds().contains(itemId);
    }

    /** Boundary IDs for the next watermark: reset to a single ID on a new timestamp, extended on a tie. */
    protected Set<Long> nextBoundaryIds(Long itemTimestamp, Long itemId, Watermark previousWatermark) {
        if (!itemTimestamp.equals(previousWatermark.getTimestamp())) {
            return Set.of(itemId);
        }
        var merged = new HashSet<Long>(previousWatermark.getBoundaryIds() != null ? previousWatermark.getBoundaryIds() : Set.of());
        merged.add(itemId);
        return merged;
    }

    /** Numeric max of two epoch-second timestamps — guards {@code persistWatermark} against ever regressing the cursor. */
    protected Long maxTimestamp(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    /**
     * Newest observed issue group's `first_detected_at`, plus every issue group ID already delivered at
     * that exact instant — resolves the case where two issue groups share the same detection timestamp
     * (second-precision collisions), which a bare `>` comparison would otherwise replay or permanently drop.
     */
    @Builder
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Watermark {
        private Long timestamp;
        private Set<Long> boundaryIds;
    }
}
