package io.kestra.plugin.aikido;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class AikidoClientTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void authenticatesAndCallsApi(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/domains")).willReturn(okJson("""
            [{"id":1,"domain":"example.com","kind":"web"}]
            """)));

        try (var client = client(wireMockRuntimeInfo)) {
            var domains = client.getArray("/domains", Map.of(), "domains:read", Map.class);
            assertThat(domains, hasSize(1));
        }
    }

    @Test
    void retriesOnceAfter401(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/api/oauth/token")).inScenario("auth")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("""
                {"access_token":"first-token","expires_in":1800}
                """))
            .willSetStateTo("reauthenticated"));
        stubFor(post(urlPathEqualTo("/api/oauth/token")).inScenario("auth")
            .whenScenarioStateIs("reauthenticated")
            .willReturn(okJson("""
                {"access_token":"second-token","expires_in":1800}
                """)));

        stubFor(get(urlPathEqualTo("/api/public/v1/domains"))
            .withHeader("Authorization", equalTo("Bearer first-token"))
            .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json").withBody("""
                {"error":"invalid_token"}
                """)));
        stubFor(get(urlPathEqualTo("/api/public/v1/domains"))
            .withHeader("Authorization", equalTo("Bearer second-token"))
            .willReturn(okJson("[]")));

        try (var client = client(wireMockRuntimeInfo)) {
            var domains = client.getArray("/domains", Map.of(), "domains:read", Map.class);
            assertThat(domains, empty());
        }
    }

    @Test
    void mapsForbiddenWithScopeHint(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/domains")).willReturn(aResponse().withStatus(403)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"reason_phrase":"missing scope"}
                """)));

        try (var client = client(wireMockRuntimeInfo)) {
            var ex = assertThrows(AikidoApiException.class, () -> client.getArray("/domains", Map.of(), "domains:read", Map.class));
            assertThat(ex.getMessage(), containsString("403"));
            assertThat(ex.getMessage(), containsString("domains:read"));
            assertThat(ex.getMessage(), containsString("missing scope"));
        }
    }

    @Test
    void authFailureNeverLeaksSecret(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/api/oauth/token")).willReturn(aResponse().withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"error":"invalid_client","error_description":"client authentication failed"}
                """)));

        try (var client = client(wireMockRuntimeInfo)) {
            var ex = assertThrows(AikidoApiException.class, () -> client.getArray("/domains", Map.of(), "domains:read", Map.class));
            assertThat(ex.getMessage(), containsString("clientId/clientSecret"));
            assertThat(ex.getMessage(), not(containsString("my-secret")));
        }
    }

    @Test
    void handlesEmptyResponseBody(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/domains")).willReturn(aResponse().withStatus(200)));

        try (var client = client(wireMockRuntimeInfo)) {
            var domains = client.getArray("/domains", Map.of(), "domains:read", Map.class);
            assertThat(domains, empty());

            var single = client.get("/domains", Map.of(), "domains:read", Map.class);
            assertThat(single, nullValue());
        }
    }

    @Test
    void streamsResponseBodyWithoutBufferingItAsAString(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/export")).willReturn(aResponse()
            .withHeader("Content-Type", "text/csv")
            .withBody("id,type\n1,sast\n")));

        try (var client = client(wireMockRuntimeInfo)) {
            var captured = new ByteArrayOutputStream();
            client.getStream("/issues/export", Map.of(), "issues:read", inputStream -> {
                try {
                    inputStream.transferTo(captured);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            assertThat(captured.toString(StandardCharsets.UTF_8), is("id,type\n1,sast\n"));
        }
    }

    @Test
    void streamMapsErrorsLikeEveryOtherCall(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/export")).willReturn(aResponse().withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"reason_phrase":"export not found"}
                """)));

        try (var client = client(wireMockRuntimeInfo)) {
            var ex = assertThrows(AikidoApiException.class, () -> client.getStream("/issues/export", Map.of(), "issues:read", inputStream -> { }));
            assertThat(ex.getMessage(), containsString("404"));
            assertThat(ex.getMessage(), containsString("export not found"));
        }
    }

    @Test
    void errorCarriesStatusCodeAndRawBody(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/groups/999")).willReturn(aResponse().withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"reason_phrase":"issue group not found"}
                """)));

        try (var client = client(wireMockRuntimeInfo)) {
            var ex = assertThrows(AikidoApiException.class, () -> client.get("/issues/groups/999", Map.of(), "issues:read", Map.class));
            assertThat(ex.getStatusCode(), is(404));
            assertThat(ex.getResponseBody(), containsString("issue group not found"));
        }
    }

    @Test
    void encodesPathSegmentsWithSpecialCharacters(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/groups/abc%20def")).willReturn(okJson("""
            {"id":42}
            """)));

        try (var client = client(wireMockRuntimeInfo)) {
            var issue = client.get("/issues/groups/abc def", Map.of(), "issues:read", Map.class);
            assertThat(issue.get("id"), is(42));
        }
    }

    private AikidoClient client(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        var runContext = runContextFactory.of(Map.of());
        var httpClient = HttpClient.builder().runContext(runContext).build();
        return new AikidoClient(runContext, httpClient, wireMockRuntimeInfo.getHttpBaseUrl(), "client-id", "my-secret");
    }
}
