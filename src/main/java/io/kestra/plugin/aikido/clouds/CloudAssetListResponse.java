package io.kestra.plugin.aikido.clouds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Internal wrapper for `GET /clouds/assets`, which — unlike the other list endpoints — wraps its items instead of returning a bare array. Never exposed as a task Output; `ListCloudAssets` flattens it into `rows`/`uri`/`size`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
class CloudAssetListResponse {
    private List<CloudAsset> assets;
    @JsonProperty("totalCount")
    private Integer totalCount;

    List<CloudAsset> assetsOrEmpty() {
        return assets == null ? List.of() : assets;
    }
}
