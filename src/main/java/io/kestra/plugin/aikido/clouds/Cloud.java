package io.kestra.plugin.aikido.clouds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An Aikido-connected cloud environment, as returned by `GET /clouds`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cloud {
    @Schema(title = "The cloud environment's ID in Aikido")
    private Long id;

    @Schema(title = "The name of the cloud environment")
    private String name;

    @Schema(title = "The cloud provider", description = "For example `aws`, `azure`, or `gcp`.")
    private String provider;

    @Schema(title = "The environment classification", description = "For example `production` or `staging`.")
    private String environment;

    @Schema(title = "The provider's external ID for this cloud environment (for example an AWS account ID)")
    @JsonProperty("external_id")
    private String externalId;

    @Schema(title = "Unix timestamp (seconds) of the last scan, or `-1` if never scanned")
    @JsonProperty("last_scanned_at")
    private Long lastScannedAt;
}
