package io.kestra.plugin.aikido.containers;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class ScanTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/containers/3/scan")).willReturn(okJson("""
            {"success":1}
            """)));

        var task = Scan.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .containerId(Property.ofValue("3"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getTriggered(), is(true));
        assertThat(output.getCompleted(), is(false));
    }

    @Test
    void mustBeActiveErrorIsActionable(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/containers/3/scan")).willReturn(aResponse().withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"error":"The container must be active before it can be scanned."}
                """)));

        var task = Scan.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .containerId(Property.ofValue("3"))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("must be active"));
        assertThat(ex.getMessage(), containsString("3"));
    }
}
