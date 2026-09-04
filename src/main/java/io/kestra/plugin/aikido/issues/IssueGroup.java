package io.kestra.plugin.aikido.issues;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** An Aikido issue group, as returned by `GET /open-issue-groups` and `GET /issues/groups/{id}`. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueGroup {
    @Schema(title = "The issue group's ID in Aikido")
    private Long id;

    @Schema(title = "A human-readable title for this issue group")
    private String title;

    @Schema(title = "A short description of this issue")
    private String description;

    @Schema(title = "The type of issues in this group", description = "One of `open_source`, `leaked_secret`, `cloud`, `iac`, `sast`, `surface_monitoring`, `malware`, `eol`, `scm_security`, `ai_pentest`, or `license`.")
    private String type;

    @Schema(title = "Numeric severity, from 1 (lowest) to 100 (highest)")
    @JsonProperty("severity_score")
    private Integer severityScore;

    @Schema(title = "Human-friendly severity", description = "One of `critical`, `high`, `medium`, or `low`.")
    private String severity;

    @Schema(title = "The current status of the issue group", description = "One of `new`, `todo`, `task_open`, `task_closed`, or `pull_request_open`.")
    @JsonProperty("group_status")
    private String groupStatus;

    @Schema(title = "Estimated time in minutes to fix this issue")
    @JsonProperty("time_to_fix_minutes")
    private Integer timeToFixMinutes;

    @Schema(title = "Locations where this issue was found")
    private List<Location> locations;

    @Schema(title = "How to fix this issue")
    @JsonProperty("how_to_fix")
    private String howToFix;

    @Schema(title = "CVE IDs related to this issue group")
    @JsonProperty("related_cve_ids")
    private List<String> relatedCveIds;

    @Schema(title = "Unix timestamp (seconds) when this issue was first detected")
    @JsonProperty("first_detected_at")
    private Long firstDetectedAt;
}
