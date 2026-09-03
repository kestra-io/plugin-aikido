package io.kestra.plugin.aikido;

import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * Base class for every Aikido task: holds the connection properties and builds an {@link AikidoClient}
 * scoped to a single task run. Not itself a plugin ({@code @Plugin}-exposed task) — subclasses under
 * the {@code issues}, {@code repositories}, {@code containers}, {@code domains}, {@code clouds}, and
 * {@code compliance} sub-packages are.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAikidoTask extends Task implements AikidoConnectionInterface {
    protected static final String DEFAULT_BASE_URL = "https://app.aikido.dev";

    /**
     * Apache HC5 defaults to no timeout at all — an unresponsive Aikido endpoint would otherwise block the worker
     * thread indefinitely. `HTTP_READ_IDLE_TIMEOUT` closes the connection after this much time with no read
     * activity — an idle timeout, not a cap on the total time to read a full (e.g. paginated) response.
     */
    protected static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration HTTP_READ_IDLE_TIMEOUT = Duration.ofSeconds(30);

    @Schema(title = BASE_URL_TITLE, description = BASE_URL_DESCRIPTION)
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

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

    /** Opens a client whose OAuth2 token is scoped to a single task run; always use in try-with-resources. */
    protected AikidoClient client(RunContext runContext) throws Exception {
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
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
}
