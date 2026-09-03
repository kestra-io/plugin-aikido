package io.kestra.plugin.aikido.issues;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A single issue record, as returned by `GET /issues/export` when `format=json`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueExportRecord {
    @Schema(title = "The issue's ID in Aikido")
    private Long id;

    @Schema(title = "The ID of the issue group this issue belongs to")
    @JsonProperty("group_id")
    private Long groupId;

    @Schema(title = "The type of this issue")
    private String type;

    @Schema(title = "The rule that flagged this issue, if applicable")
    private String rule;

    @Schema(title = "Where this issue was found", description = "For example `code_repository`, `container`, or `cloud`.")
    @JsonProperty("attack_surface")
    private String attackSurface;

    @Schema(title = "Numeric severity, from 1 (lowest) to 100 (highest)")
    @JsonProperty("severity_score")
    private Integer severityScore;

    @Schema(title = "Human-friendly severity", description = "One of `critical`, `high`, `medium`, or `low`.")
    private String severity;

    @Schema(title = "The current status of this issue")
    private String status;

    @Schema(title = "The affected package, for open-source/license issues")
    @JsonProperty("affected_package")
    private String affectedPackage;

    @Schema(title = "The affected file, for SAST/IaC/secrets issues")
    @JsonProperty("affected_file")
    private String affectedFile;

    @Schema(title = "The related CVE ID, if applicable")
    @JsonProperty("cve_id")
    private String cveId;

    @Schema(title = "Unix timestamp (seconds) when this issue was first detected")
    @JsonProperty("first_detected_at")
    private Long firstDetectedAt;

    @Schema(title = "ID of the code repository this issue was found in, if applicable")
    @JsonProperty("code_repo_id")
    private Long codeRepoId;

    @Schema(title = "Name of the code repository this issue was found in, if applicable")
    @JsonProperty("code_repo_name")
    private String codeRepoName;

    @Schema(title = "ID of the container this issue was found in, if applicable")
    @JsonProperty("container_repo_id")
    private Long containerRepoId;

    @Schema(title = "Name of the container this issue was found in, if applicable")
    @JsonProperty("container_repo_name")
    private String containerRepoName;

    @Schema(title = "ID of the domain this issue was found on, if applicable")
    @JsonProperty("domain_id")
    private Long domainId;

    @Schema(title = "Name of the domain this issue was found on, if applicable")
    @JsonProperty("domain_name")
    private String domainName;

    @Schema(title = "Unix timestamp (seconds) this issue is snoozed until, if snoozed")
    @JsonProperty("snooze_until")
    private Long snoozeUntil;
}
