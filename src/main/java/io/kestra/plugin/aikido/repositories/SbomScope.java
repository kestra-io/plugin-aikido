package io.kestra.plugin.aikido.repositories;

/** Dependency scope for `ExportSbom`. */
public enum SbomScope {
    ALL,
    ONLY_DEV_DEPS,
    EXCLUDE_DEV_DEPS
}
