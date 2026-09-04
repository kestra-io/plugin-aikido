package io.kestra.plugin.aikido;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/** Shared OAuth2 token stub reused by every task/trigger test — every Aikido call authenticates first. */
public final class AikidoWireMockStubs {
    private AikidoWireMockStubs() {
    }

    public static void stubAuth() {
        stubFor(post(urlPathEqualTo("/api/oauth/token")).willReturn(okJson("""
            {"access_token":"test-token","expires_in":1800,"token_type":"bearer"}
            """)));
    }
}
