package io.kestra.plugin.aikido.repositories;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.aikido.AikidoApiException;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
    void triggersScanWithoutWaiting(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/repositories/code/7/scan")).willReturn(aResponse().withStatus(204)));

        var task = Scan.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .repositoryId(Property.ofValue("7"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getTriggered(), is(true));
        assertThat(output.getCompleted(), is(false));
    }

    @Test
    void mustBeActiveErrorIsActionable(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/repositories/code/7/scan")).willReturn(aResponse().withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"error":"The repository must be active before it can be scanned."}
                """)));

        var task = Scan.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .repositoryId(Property.ofValue("7"))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("must be active"));
        assertThat(ex.getMessage(), containsString("7"));
    }

    @Test
    void unrelatedBadRequestIsNotReportedAsInactive(WireMockRuntimeInfo wireMockRuntimeInfo) {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/repositories/code/7/scan")).willReturn(aResponse().withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"error":"Invalid scan options."}
                """)));

        var task = Scan.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .repositoryId(Property.ofValue("7"))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(AikidoApiException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Invalid scan options"));
    }

    @Test
    void waitsForLastScannedAtToAdvance(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/repositories/code/7/scan")).willReturn(aResponse().withStatus(204)));

        stubFor(get(urlPathEqualTo("/api/public/v1/repositories/code/7")).inScenario("scan").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("""
                {"id":7,"name":"repo","provider":"github","external_repo_id":"1","last_scanned_at":100}
                """))
            .willSetStateTo("scanning"));
        stubFor(get(urlPathEqualTo("/api/public/v1/repositories/code/7")).inScenario("scan").whenScenarioStateIs("scanning")
            .willReturn(okJson("""
                {"id":7,"name":"repo","provider":"github","external_repo_id":"1","last_scanned_at":200}
                """)));

        var task = Scan.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .repositoryId(Property.ofValue("7"))
            .waitForCompletion(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getTriggered(), is(true));
        assertThat(output.getCompleted(), is(true));
    }
}
