package io.kestra.plugin.aikido.containers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An Aikido container repository, as returned by `GET /containers`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Container {
    @Schema(title = "The container repository's ID in Aikido")
    private Long id;

    @Schema(title = "The name of the container repository")
    private String name;

    @Schema(title = "Where the container image is hosted")
    private String provider;

    @Schema(title = "The ID of the connected cloud this container belongs to, if any")
    @JsonProperty("cloud_id")
    private Long cloudId;

    @Schema(title = "The ID of the container registry")
    @JsonProperty("registry_id")
    private String registryId;

    @Schema(title = "The name of the container registry")
    @JsonProperty("registry_name")
    private String registryName;

    @Schema(title = "The image tag Aikido scans")
    private String tag;

    @Schema(title = "Base image distro, if detected")
    private String distro;

    @Schema(title = "Unix timestamp (seconds) of the last scan, or `-1` if never scanned")
    @JsonProperty("last_scanned_at")
    private Long lastScannedAt;

    @Schema(title = "ID of the code repository linked to this container, if any")
    @JsonProperty("linked_code_repo_id")
    private Long linkedCodeRepoId;

    @Schema(title = "Whether this container repository is active")
    @JsonProperty("is_active")
    private Boolean isActive;
}
