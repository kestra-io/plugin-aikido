package io.kestra.plugin.aikido.clouds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/** A single cloud asset, as returned by `GET /clouds/assets`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudAsset {
    @Schema(title = "The Aikido cloud asset ID")
    private Long id;

    @Schema(title = "The provider-specific type of the asset", description = "For example `AWS::EC2::Instance`.")
    @JsonProperty("asset_type")
    private String assetType;

    @Schema(title = "The display name of the asset")
    @JsonProperty("asset_name")
    private String assetName;

    @Schema(title = "The provider-specific identifier of the asset")
    @JsonProperty("source_id")
    private String sourceId;

    @Schema(title = "The region where the asset is located")
    private String region;

    @Schema(title = "The ID of the connected cloud this asset belongs to")
    @JsonProperty("cloud_id")
    private Long cloudId;

    @Schema(title = "The cloud provider for this asset")
    private String provider;

    @Schema(title = "Full asset metadata, mirroring the cloud provider's own representation", description = "Only populated when `includeMetadata` is enabled.")
    private Map<String, Object> metadata;

    @Schema(title = "Projected metadata values selected through `metadataFields`")
    @JsonProperty("selected_metadata")
    private Map<String, Object> selectedMetadata;
}
