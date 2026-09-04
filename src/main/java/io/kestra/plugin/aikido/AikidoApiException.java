package io.kestra.plugin.aikido;

/**
 * User-facing error for any failed Aikido API call: authentication, HTTP error status, or an unparsable response.
 *
 * <p>When the failure was an HTTP error response, {@link #getStatusCode()} and {@link #getResponseBody()} carry
 * the raw status and body so callers can branch on them instead of substring-matching the formatted message.
 */
public class AikidoApiException extends Exception {
    private final Integer statusCode;
    private final String responseBody;

    public AikidoApiException(String message) {
        this(message, null, null, null);
    }

    public AikidoApiException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public AikidoApiException(String message, Integer statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    public AikidoApiException(String message, Integer statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** HTTP status of the failed response, or {@code null} when the failure wasn't an HTTP error response. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** Raw response body as returned by Aikido, or {@code null} when the failure wasn't an HTTP error response. */
    public String getResponseBody() {
        return responseBody;
    }
}
