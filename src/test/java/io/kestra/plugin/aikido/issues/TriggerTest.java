package io.kestra.plugin.aikido.issues;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest
class TriggerTest {
    @Inject
    private RunContextFactory runContextFactory;

    private Trigger trigger(WireMockRuntimeInfo wireMockRuntimeInfo, int threshold) {
        return Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .severityThreshold(Property.ofValue(threshold))
            .interval(Duration.ofMinutes(5))
            .build();
    }

    @Test
    void firstPollSeedsBaselineAndDoesNotFire(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).willReturn(okJson("""
            [{"id":1,"title":"Old issue","type":"sast","severity":"high","severity_score":80,"group_status":"new","first_detected_at":1700000000}]
            """)));

        var trigger = trigger(wireMockRuntimeInfo, 50);
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        var result = trigger.evaluate(ctx.getKey(), ctx.getValue());

        assertThat("First poll must not fire", result.isPresent(), is(false));
    }

    @Test
    void firesOnlyOnNewIssueAboveThresholdAndDoesNotRefire(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();

        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).inScenario("new-issue")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("""
                [{"id":1,"title":"Old issue","type":"sast","severity":"high","severity_score":80,"group_status":"new","first_detected_at":1700000000}]
                """))
            .willSetStateTo("baseline-recorded"));

        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).inScenario("new-issue")
            .whenScenarioStateIs("baseline-recorded")
            .willReturn(okJson("""
                [
                  {"id":1,"title":"Old issue","type":"sast","severity":"high","severity_score":80,"group_status":"new","first_detected_at":1700000000},
                  {"id":2,"title":"New critical issue","type":"leaked_secret","severity":"critical","severity_score":95,"group_status":"new","first_detected_at":1700001000}
                ]
                """))
            .willSetStateTo("second-poll-done"));

        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).inScenario("new-issue")
            .whenScenarioStateIs("second-poll-done")
            .willReturn(okJson("""
                [
                  {"id":1,"title":"Old issue","type":"sast","severity":"high","severity_score":80,"group_status":"new","first_detected_at":1700000000},
                  {"id":2,"title":"New critical issue","type":"leaked_secret","severity":"critical","severity_score":95,"group_status":"new","first_detected_at":1700001000}
                ]
                """)));

        var trigger = trigger(wireMockRuntimeInfo, 50);
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        var firstPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("First poll must not fire", firstPoll.isPresent(), is(false));

        var secondPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("Second poll must fire on the new issue", secondPoll.isPresent(), is(true));

        var variables = secondPoll.get().getTrigger().getVariables();
        assertThat(variables.get("issueGroupId"), is(2L));
        assertThat(variables.get("issueType"), is("leaked_secret"));
        assertThat(variables.get("severityScore"), is(95));

        var thirdPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("Third poll must not re-fire the already-delivered issue", thirdPoll.isPresent(), is(false));
    }

    @Test
    void ignoresIssuesBelowSeverityThreshold(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();

        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).inScenario("low-severity")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("[]"))
            .willSetStateTo("baseline-recorded"));

        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).inScenario("low-severity")
            .whenScenarioStateIs("baseline-recorded")
            .willReturn(okJson("""
                [{"id":3,"title":"Low severity issue","type":"sast","severity":"low","severity_score":10,"group_status":"new","first_detected_at":1700002000}]
                """)));

        var trigger = trigger(wireMockRuntimeInfo, 80);
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        trigger.evaluate(ctx.getKey(), ctx.getValue());
        var secondPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());

        assertThat("An issue below severityThreshold must never fire", secondPoll.isPresent(), is(false));
    }
}
