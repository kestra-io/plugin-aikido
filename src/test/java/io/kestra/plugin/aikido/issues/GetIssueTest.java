package io.kestra.plugin.aikido.issues;

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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class GetIssueTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/groups/42")).willReturn(okJson("""
            {"id":42,"title":"Vulnerable dependency","type":"open_source","severity":"high","severity_score":75,"group_status":"todo"}
            """)));

        var task = GetIssue.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .issueGroupId(Property.ofValue("42"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getIssue().getId(), is(42L));
        assertThat(output.getIssue().getTitle(), is("Vulnerable dependency"));
    }

    @Test
    void encodesIssueGroupIdWithSpecialCharacters(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/groups/abc%20def")).willReturn(okJson("""
            {"id":42,"title":"Vulnerable dependency","type":"open_source","severity":"high","severity_score":75,"group_status":"todo"}
            """)));

        var task = GetIssue.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .issueGroupId(Property.ofValue("abc def"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getIssue().getId(), is(42L));
    }

    @Test
    void notFoundFailsWithActionableMessage(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/groups/999")).willReturn(aResponse().withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"reason_phrase":"issue group not found"}
                """)));

        var task = GetIssue.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .issueGroupId(Property.ofValue("999"))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("999"));
    }
}
