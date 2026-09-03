package io.kestra.plugin.aikido;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Closeable;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Wraps a Kestra {@link HttpClient} with Aikido's OAuth2 client-credentials flow (acquire on first
 * use, transparently re-acquire on expiry or on a 401), query-string building, and non-2xx error
 * mapping. One instance is scoped to a single task run or trigger poll — its token cache is instance
 * state, never static/JVM-wide, so credentials never leak across tenants or namespaces sharing the
 * same plugin classloader.
 */
public final class AikidoClient implements Closeable {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 2;

    private final RunContext runContext;
    private final HttpClient httpClient;
    private final String apiBaseUrl;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private String accessToken;
    private Instant tokenExpiresAt;

    public AikidoClient(RunContext runContext, HttpClient httpClient, String baseUrl, String clientId, String clientSecret) {
        this.runContext = runContext;
        this.httpClient = httpClient;
        var normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiBaseUrl = normalizedBaseUrl + "/api/public/v1";
        this.tokenUrl = normalizedBaseUrl + "/api/oauth/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public <T> T get(String path, Map<String, Object> query, String scope, Class<T> type) throws Exception {
        return parse(raw("GET", path, query, null, scope, "call Aikido API GET " + path).getBody(), type);
    }

    public <T> List<T> getArray(String path, Map<String, Object> query, String scope, Class<T> itemType) throws Exception {
        var body = raw("GET", path, query, null, scope, "call Aikido API GET " + path).getBody();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        var listType = MAPPER.getTypeFactory().constructCollectionType(List.class, itemType);
        return MAPPER.readValue(body, listType);
    }

    /**
     * Streams the raw response body to {@code consumer} instead of buffering it as a {@code String} —
     * used for exports/SBOMs whose payload size is unbounded. The stream is only valid for the duration
     * of the call; the caller must fully consume or copy it before returning.
     */
    public void getStream(String path, Map<String, Object> query, String scope, Consumer<InputStream> consumer) throws Exception {
        streamRaw("GET", path, query, scope, "export from Aikido API GET " + path, consumer, 0, 0);
    }

    private void streamRaw(String method, String path, Map<String, Object> query, String scope, String action, Consumer<InputStream> consumer, int authRetries, int rateLimitRetries) throws Exception {
        var request = HttpRequest.builder()
            .method(method)
            .uri(buildUri(apiBaseUrl, path, query))
            .addHeader("Authorization", "Bearer " + token())
            .addHeader("Accept", "*/*")
            .build();

        try {
            httpClient.request(request, response -> consumer.accept(response.getBody() != null ? response.getBody() : InputStream.nullInputStream()));
        } catch (HttpClientResponseException e) {
            var status = e.getResponse().getStatus().getCode();

            if (status == 401 && authRetries == 0) {
                accessToken = null;
                streamRaw(method, path, query, scope, action, consumer, authRetries + 1, rateLimitRetries);
                return;
            }

            if (status == 429 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                sleepBeforeRetry(retryAfterSeconds(e));
                streamRaw(method, path, query, scope, action, consumer, authRetries, rateLimitRetries + 1);
                return;
            }

            throw mapError(e, scope, action);
        }
    }

    public <T> T put(String path, Object jsonBody, String scope, Class<T> type) throws Exception {
        return parse(raw("PUT", path, null, jsonBody, scope, "call Aikido API PUT " + path).getBody(), type);
    }

    public HttpResponse<String> post(String path, Map<String, Object> query, Object jsonBody, String scope) throws Exception {
        return raw("POST", path, query, jsonBody, scope, "call Aikido API POST " + path);
    }

    public <T> T post(String path, Map<String, Object> query, Object jsonBody, String scope, Class<T> type) throws Exception {
        return parse(post(path, query, jsonBody, scope).getBody(), type);
    }

    private <T> T parse(String body, Class<T> type) throws Exception {
        if (body == null || body.isBlank()) {
            return null;
        }
        return MAPPER.readValue(body, type);
    }

    private HttpResponse<String> raw(String method, String path, Map<String, Object> query, Object jsonBody, String scope, String action) throws Exception {
        return raw(method, path, query, jsonBody, scope, action, 0, 0);
    }

    private HttpResponse<String> raw(String method, String path, Map<String, Object> query, Object jsonBody, String scope, String action, int authRetries, int rateLimitRetries) throws Exception {
        var builder = HttpRequest.builder()
            .method(method)
            .uri(buildUri(apiBaseUrl, path, query))
            .addHeader("Authorization", "Bearer " + token())
            .addHeader("Accept", "application/json");

        if (jsonBody != null) {
            builder
                .addHeader("Content-Type", "application/json")
                .body(HttpRequest.JsonRequestBody.builder().content(jsonBody).build());
        }

        try {
            return httpClient.request(builder.build(), String.class);
        } catch (HttpClientResponseException e) {
            var status = e.getResponse().getStatus().getCode();

            if (status == 401 && authRetries == 0) {
                accessToken = null;
                return raw(method, path, query, jsonBody, scope, action, authRetries + 1, rateLimitRetries);
            }

            if (status == 429 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                sleepBeforeRetry(retryAfterSeconds(e));
                return raw(method, path, query, jsonBody, scope, action, authRetries, rateLimitRetries + 1);
            }

            throw mapError(e, scope, action);
        }
    }

    private void sleepBeforeRetry(long seconds) throws InterruptedException {
        Thread.sleep(Math.max(seconds, 1) * 1000L);
    }

    private long retryAfterSeconds(HttpClientResponseException e) {
        try {
            return Long.parseLong(e.getResponse().getHeaders().firstValue("Retry-After").orElse(String.valueOf(DEFAULT_RETRY_AFTER_SECONDS)));
        } catch (NumberFormatException nfe) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    private String token() throws AikidoApiException {
        if (accessToken == null || Instant.now().isAfter(tokenExpiresAt)) {
            authenticate();
        }
        return accessToken;
    }

    private void authenticate() throws AikidoApiException {
        var request = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(tokenUrl))
            .addHeader("Authorization", basicAuth())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .body(HttpRequest.JsonRequestBody.builder().content(Map.of("grant_type", "client_credentials")).build())
            .build();

        try {
            var response = httpClient.request(request, String.class);
            var token = MAPPER.readValue(response.getBody(), TokenResponse.class);
            if (token.getAccessToken() == null || token.getAccessToken().isBlank()) {
                throw new AikidoApiException("Aikido authentication succeeded but returned no access token — verify 'baseUrl' matches your Aikido account region.");
            }
            this.accessToken = token.getAccessToken();
            var ttlSeconds = token.getExpiresIn() != null ? token.getExpiresIn() : 900;
            // Refresh at 90% of the token's lifetime to absorb clock skew and in-flight request latency.
            this.tokenExpiresAt = Instant.now().plusSeconds(Math.max((long) (ttlSeconds * 0.9), 30));
        } catch (HttpClientResponseException e) {
            throw new AikidoApiException("Aikido authentication failed — check clientId/clientSecret and that 'baseUrl' matches your account region: " + errorMessage(e));
        } catch (AikidoApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AikidoApiException("Failed to parse Aikido authentication response: " + e.getMessage(), e);
        }
    }

    private String basicAuth() {
        var raw = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private AikidoApiException mapError(HttpClientResponseException e, String scope, String action) {
        var status = e.getResponse().getStatus().getCode();
        var message = errorMessage(e);

        var hint = switch (status) {
            case 403 -> " Hint: the API client is missing the required '" + scope + "' scope for this operation.";
            case 429 -> " Hint: Aikido rate limit exceeded — reduce call frequency or polling interval.";
            default -> "";
        };

        return new AikidoApiException("Failed to " + action + ": HTTP " + status + " - " + message + hint);
    }

    /** Never includes the client secret or the Basic auth header — only the response body Aikido itself returned. */
    private String errorMessage(HttpClientResponseException e) {
        var rawBody = e.getResponse().getBody();
        var body = switch (rawBody) {
            case null -> "";
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> String.valueOf(rawBody);
        };

        if (!body.isBlank()) {
            try {
                var envelope = MAPPER.readValue(body, ErrorEnvelope.class);
                var extracted = envelope.getReasonPhrase() != null ? envelope.getReasonPhrase()
                    : envelope.getError() != null ? envelope.getError()
                    : envelope.getErrorDescription();
                if (extracted != null && !extracted.isBlank()) {
                    return extracted;
                }
            } catch (Exception parseFailure) {
                // Body wasn't a JSON error envelope; fall through to using the raw body below.
            }
        }

        return body.isBlank() ? "empty response from Aikido API" : body;
    }

    private URI buildUri(String base, String path, Map<String, Object> query) {
        var resolvedPath = path.startsWith("/") ? path : "/" + path;
        // Every segment is encoded, not just the interpolated IDs — literal segments (e.g. "issues", "scan")
        // are plain ASCII and round-trip unchanged, so this is safe without the caller having to know which
        // segments are dynamic.
        var encodedPath = Arrays.stream(resolvedPath.split("/", -1))
            .map(segment -> segment.isEmpty() ? segment : encodePathSegment(segment))
            .collect(Collectors.joining("/"));
        var params = new LinkedHashMap<String, Object>();
        if (query != null) {
            query.forEach((key, value) -> {
                if (value != null) {
                    params.put(key, value);
                }
            });
        }
        return URI.create(base + encodedPath + buildQueryString(params));
    }

    private String encodePathSegment(String segment) {
        return encode(segment).replace("+", "%20");
    }

    private String buildQueryString(Map<String, Object> params) {
        if (params.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        params.forEach((key, value) -> {
            if (value instanceof Iterable<?> iterable) {
                for (var item : iterable) {
                    if (item != null) {
                        parts.add(encode(key) + "=" + encode(String.valueOf(item)));
                    }
                }
            } else {
                parts.add(encode(key) + "=" + encode(String.valueOf(value)));
            }
        });
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            runContext.logger().warn("Failed to close Aikido HTTP client: {}", e.getMessage());
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;
        @JsonProperty("expires_in")
        private Integer expiresIn;
        @JsonProperty("token_type")
        private String tokenType;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ErrorEnvelope {
        @JsonProperty("reason_phrase")
        private String reasonPhrase;
        private String error;
        @JsonProperty("error_description")
        private String errorDescription;
    }
}
