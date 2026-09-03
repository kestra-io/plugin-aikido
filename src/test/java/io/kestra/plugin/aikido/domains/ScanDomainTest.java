package io.kestra.plugin.aikido.domains;

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
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
class ScanDomainTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/domains/scan"))
            .withRequestBody(matchingJsonPath("$.domain_id", com.github.tomakehurst.wiremock.client.WireMock.equalTo("5")))
            .willReturn(okJson("""
                {"success":1}
                """)));

        var task = ScanDomain.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .domainId(Property.ofValue("5"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getTriggered(), is(true));
        assertThat(output.getCompleted(), is(false));
    }

    @Test
    void nonNumericDomainIdFailsWithActionableMessage() {
        var task = ScanDomain.builder()
            .baseUrl(Property.ofValue("http://localhost"))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .domainId(Property.ofValue("not-a-number"))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("domainId"));
        assertThat(ex.getMessage(), containsString("not-a-number"));
    }

    @Test
    void notFoundDomainFailsWithMessage(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/domains/scan")).willReturn(aResponse().withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"reason_phrase":"domain not found"}
                """)));

        var task = ScanDomain.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .domainId(Property.ofValue("999"))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("domain not found"));
    }
}
