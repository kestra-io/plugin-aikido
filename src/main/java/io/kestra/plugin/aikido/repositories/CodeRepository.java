package io.kestra.plugin.aikido.repositories;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An Aikido code repository, as returned by `GET /repositories/code`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeRepository {
    @Schema(title = "The repository's ID in Aikido")
    private Long id;

    @Schema(title = "The name of the code repository")
    private String name;

    @Schema(title = "Where the code repository is hosted", description = "One of `github`, `gitlab`, `gitlab-server`, `bitbucket`, `azure_devops`, or `selfscan`.")
    private String provider;

    @Schema(title = "The ID of the repository from the provider")
    @JsonProperty("external_repo_id")
    private String externalRepoId;

    @Schema(title = "External URL to the repository")
    private String url;

    @Schema(title = "The branch Aikido scans")
    private String branch;

    @Schema(title = "Unix timestamp (seconds) of the last scan, or `-1` if never scanned")
    @JsonProperty("last_scanned_at")
    private Long lastScannedAt;

    @Schema(title = "Connectivity classification of this repository, if requested")
    private String connectivity;

    @Schema(title = "Sensitivity classification of this repository, if requested")
    private String sensitivity;
}
