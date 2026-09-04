package io.kestra.plugin.aikido;

/**
 * Shared {@code @Schema} wording for the Aikido connection properties. A task and a trigger cannot
 * share a common superclass ({@link io.kestra.core.models.tasks.Task} vs.
 * {@link io.kestra.core.models.triggers.AbstractTrigger}), so both {@link AbstractAikidoTask} and
 * {@link AbstractAikidoTrigger} redeclare these fields; centralizing the titles/descriptions here
 * keeps the two declarations from drifting apart.
 */
public interface AikidoConnectionInterface {
    String BASE_URL_TITLE = "Aikido API base URL";
    String BASE_URL_DESCRIPTION = """
        Base URL of the Aikido Security application for your account's region: `https://app.aikido.dev` (Europe, \
        default), `https://app.us.aikido.dev` (United States), `https://app.au.aikido.dev` (Australia), or \
        `https://app.me.aikido.dev` (Middle East). The OAuth2 token endpoint is derived from this same host, so a \
        region override applies to both authentication and API calls.
        """;

    String CLIENT_ID_TITLE = "OAuth2 client ID";
    String CLIENT_ID_DESCRIPTION = """
        Client ID of an Aikido API client with the OAuth2 scopes required by the tasks and triggers being used \
        (for example `issues:read`, `repositories:write`). Client credentials are created in the Aikido console \
        under Settings > API access.
        """;

    String CLIENT_SECRET_TITLE = "OAuth2 client secret";
    String CLIENT_SECRET_DESCRIPTION = """
        Client secret of the Aikido API client. Exchanged for a short-lived JWT Bearer token on first use (and \
        transparently refreshed before it expires); never logged or included in error messages.
        """;
}
