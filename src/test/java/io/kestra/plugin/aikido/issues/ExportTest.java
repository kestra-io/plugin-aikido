package io.kestra.plugin.aikido.issues;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import org.junit.jupiter.api.Test;

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
class ExportTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void fetchJson(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/export")).willReturn(okJson("""
            [{"id":1,"group_id":10,"type":"sast","severity":"high","severity_score":70,"status":"open","first_detected_at":1700000000}]
            """)));

        var task = Export.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .format(Property.ofValue(ExportFormat.JSON))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getSize(), is(1L));
    }

    @Test
    void storeJson(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/export")).willReturn(okJson("""
            [{"id":1,"group_id":10,"type":"sast","severity":"high","severity_score":70,"status":"open"}]
            """)));

        var task = Export.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), is(1L));
    }

    @Test
    void csvFormatAlwaysStores(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/issues/export"))
            .withQueryParam("format", equalTo("csv"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                .withHeader("Content-Type", "text/csv")
                .withBody("id,type,severity\n1,sast,high\n")));

        var task = Export.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .format(Property.ofValue(ExportFormat.CSV))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var output = task.run(runContext);

        assertThat(output.getUri(), notNullValue());
        try (var inputStream = runContext.storage().getFile(output.getUri())) {
            var content = new String(inputStream.readAllBytes());
            assertThat(content, is("id,type,severity\n1,sast,high\n"));
        }
    }
}
