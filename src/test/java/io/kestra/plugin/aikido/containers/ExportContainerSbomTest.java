package io.kestra.plugin.aikido.containers;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import io.kestra.plugin.aikido.SbomFormat;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@WireMockTest
class ExportContainerSbomTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/containers/3/licenses/export")).willReturn(aResponse()
            .withHeader("Content-Type", "text/csv")
            .withBody("package,version,license\nlodash,4.17.21,MIT\n")));

        var task = ExportContainerSbom.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .containerId(Property.ofValue("3"))
            .format(Property.ofValue(SbomFormat.CSV))
            .build();

        var runContext = runContextFactory.of(Map.of());
        var output = task.run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), greaterThan(0L));
        try (var inputStream = runContext.storage().getFile(output.getUri())) {
            var content = new String(inputStream.readAllBytes());
            assertThat(content, containsString("lodash"));
        }
    }
}
