package io.kestra.plugin.aikido.issues;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@WireMockTest
class ListOpenIssuesTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void fetchFiltersBySeverityClientSide(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).willReturn(okJson("""
            [
              {"id":1,"title":"Critical issue","type":"sast","severity":"critical","severity_score":95,"group_status":"new"},
              {"id":2,"title":"Low issue","type":"sast","severity":"low","severity_score":10,"group_status":"new"}
            ]
            """)));

        var task = ListOpenIssues.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .severities(Property.ofValue(List.of(Severity.CRITICAL)))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRows().getFirst().getId(), is(1L));
        assertThat(output.getSize(), is(1L));
    }

    @Test
    void paginatesAcrossPages(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups"))
            .withQueryParam("page", equalTo("0"))
            .willReturn(okJson("""
                [{"id":1,"title":"Issue 1","type":"sast","severity":"high","severity_score":70,"group_status":"new"}]
                """)));
        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups"))
            .withQueryParam("page", equalTo("1"))
            .willReturn(okJson("[]")));

        var task = ListOpenIssues.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .pageSize(Property.ofValue(1))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRows(), hasSize(1));
    }

    @Test
    void storeStreamsToInternalStorage(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).willReturn(okJson("""
            [{"id":1,"title":"Issue 1","type":"sast","severity":"high","severity_score":70,"group_status":"new"}]
            """)));

        var task = ListOpenIssues.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var output = task.run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), is(1L));
        try (var inputStream = runContext.storage().getFile(output.getUri());
             var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            var lines = FileSerde.readAll(reader).count().block();
            assertThat(lines, is(1L));
        }
    }

    @Test
    void emptyResultReturnsZeroSize(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/open-issue-groups")).willReturn(okJson("[]")));

        var task = ListOpenIssues.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getSize(), is(0L));
        assertThat(output.getRows(), hasSize(0));
    }
}
