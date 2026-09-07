package io.kestra.plugin.aikido.compliance;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class GetReportTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/report/iso/overview")).willReturn(okJson("""
            [{"overview":{"technological_controls":[]},"total_complying_rule_count":1,"total_rule_count":3}]
            """)));

        var task = GetReport.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .framework(Property.ofValue(ComplianceFramework.ISO27001))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getOverview(), notNullValue());
        assertThat(output.getTotalComplyingRuleCount(), is(1));
        assertThat(output.getTotalRuleCount(), is(3));
    }

    @Test
    void emptyArrayThrowsActionableException(WireMockRuntimeInfo wireMockRuntimeInfo) {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/report/soc2/overview")).willReturn(okJson("[]")));

        var task = GetReport.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .framework(Property.ofValue(ComplianceFramework.SOC2))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));

        assertThat(exception.getMessage(), is("Aikido returned no compliance data for framework 'SOC2' — verify the account has this framework enabled."));
    }

    @Test
    void soc2RequestsSoc2Path(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        assertRequestsFrameworkPath(wireMockRuntimeInfo, ComplianceFramework.SOC2, "soc2");
    }

    @Test
    void iso27001RequestsIsoPath(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        assertRequestsFrameworkPath(wireMockRuntimeInfo, ComplianceFramework.ISO27001, "iso");
    }

    @Test
    void nis2RequestsNis2Path(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        assertRequestsFrameworkPath(wireMockRuntimeInfo, ComplianceFramework.NIS2, "nis2");
    }

    private void assertRequestsFrameworkPath(WireMockRuntimeInfo wireMockRuntimeInfo, ComplianceFramework framework, String pathSegment) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/report/" + pathSegment + "/overview")).willReturn(okJson("""
            [{"overview":{},"total_complying_rule_count":2,"total_rule_count":5}]
            """)));

        var task = GetReport.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .framework(Property.ofValue(framework))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getTotalComplyingRuleCount(), is(2));
        assertThat(output.getTotalRuleCount(), is(5));
        verify(getRequestedFor(urlPathEqualTo("/api/public/v1/report/" + pathSegment + "/overview")));
    }
}
