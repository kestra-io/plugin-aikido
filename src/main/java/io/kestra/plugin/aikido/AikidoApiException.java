package io.kestra.plugin.aikido;

/** User-facing error for any failed Aikido API call: authentication, HTTP error status, or an unparsable response. */
public class AikidoApiException extends Exception {
    public AikidoApiException(String message) {
        super(message);
    }

    public AikidoApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
