package io.kestra.plugin.aikido.issues;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SnoozeIssueTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        var until = Instant.now().plus(30, ChronoUnit.DAYS);
        stubFor(put(urlPathEqualTo("/api/public/v1/issues/groups/42/snooze"))
            .withRequestBody(matchingJsonPath("$.snooze_until"))
            .withRequestBody(matchingJsonPath("$.reason", com.github.tomakehurst.wiremock.client.WireMock.equalTo("false positive")))
            .willReturn(okJson("""
                {"success":true,"snoozed_single_issues_amount":3}
                """)));

        var task = SnoozeIssue.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .issueGroupId(Property.ofValue("42"))
            .until(Property.ofValue(until))
            .reason(Property.ofValue("false positive"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getSuccess(), is(true));
        assertThat(output.getSnoozedSingleIssuesAmount(), is(3));
    }

    @Test
    void rejectsPastUntil() {
        var task = SnoozeIssue.builder()
            .baseUrl(Property.ofValue("http://localhost"))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .issueGroupId(Property.ofValue("42"))
            .until(Property.ofValue(Instant.now().minus(1, ChronoUnit.DAYS)))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("past"));
    }
}
