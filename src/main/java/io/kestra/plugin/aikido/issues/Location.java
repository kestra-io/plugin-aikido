package io.kestra.plugin.aikido.issues;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A place where an Aikido issue was found (a code repository, a container, or a cloud environment). */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Location {
    @Schema(title = "The location's ID in Aikido")
    private Long id;

    @Schema(title = "The location's name")
    private String name;

    @Schema(title = "The location's type", description = "One of `cloud`, `code_repo`, or `container_repo`.")
    private String type;
}
