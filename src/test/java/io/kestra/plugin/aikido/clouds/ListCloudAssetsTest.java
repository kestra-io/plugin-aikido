package io.kestra.plugin.aikido.clouds;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.aikido.AikidoWireMockStubs;
import jakarta.inject.Inject;
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

@KestraTest
@WireMockTest
class ListCloudAssetsTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void fetchesWrappedAssetsUsingLimitPagination(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        AikidoWireMockStubs.stubAuth();
        stubFor(get(urlPathEqualTo("/api/public/v1/clouds/assets"))
            .withQueryParam("limit", equalTo("20"))
            .willReturn(okJson("""
                {
                  "assets": [
                    {"id":101,"asset_type":"AWS::EC2::Instance","asset_name":"web-server-1","source_id":"i-0123","region":"eu-west-1","cloud_id":12,"provider":"aws"}
                  ],
                  "totalCount": 1
                }
                """)));

        var task = ListCloudAssets.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .clientId(Property.ofValue("client-id"))
            .clientSecret(Property.ofValue("client-secret"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRows().getFirst().getAssetName(), is("web-server-1"));
        assertThat(output.getSize(), is(1L));
    }
}
