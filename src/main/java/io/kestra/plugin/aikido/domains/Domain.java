package io.kestra.plugin.aikido.domains;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An Aikido-monitored domain, as returned by `GET /domains`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Domain {
    @Schema(title = "The domain's ID in Aikido")
    private Long id;

    @Schema(title = "The domain name")
    private String domain;

    @Schema(title = "The kind of domain monitoring configured")
    private String kind;

    @Schema(title = "Whether authenticated scanning is configured for this domain")
    @JsonProperty("is_auth_configured")
    private Boolean isAuthConfigured;

    @Schema(title = "Unix timestamp (seconds) of the last scan, or `-1` if never scanned")
    @JsonProperty("last_scanned_at")
    private Long lastScannedAt;
}
