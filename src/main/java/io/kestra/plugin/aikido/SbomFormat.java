package io.kestra.plugin.aikido;

/** Output format for the repository and container SBOM/license export tasks. Shared across both sub-packages since Aikido exposes the identical format enum on both endpoints. */
public enum SbomFormat {
    CSV,
    SBOM,
    SBOM_SPDX
}
