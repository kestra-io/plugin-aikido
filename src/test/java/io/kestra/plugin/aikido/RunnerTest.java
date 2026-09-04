package io.kestra.plugin.aikido;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@KestraTest(startRunner = true)
@WireMockTest(httpPort = 18099)
class RunnerTest {
    @BeforeEach
    void stubs() {
        AikidoWireMockStubs.stubAuth();
        stubFor(post(urlPathEqualTo("/api/public/v1/repositories/code/7/scan")).willReturn(aResponse().withStatus(204)));
    }

    @Test
    @ExecuteFlow("sanity-checks/aikido_scan_repository.yaml")
    void aikido_scan_repository(Execution execution) {
        assertThat(execution.getTaskRunList(), hasSize(2));
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }
}
