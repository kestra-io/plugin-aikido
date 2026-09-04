package io.kestra.plugin.aikido.compliance;

/** Compliance framework supported by `GetReport`; maps to Aikido's own lowercase path segments (`ISO27001` maps to `iso`). */
public enum ComplianceFramework {
    NIS2,
    SOC2,
    ISO27001;

    String pathSegment() {
        return this == ISO27001 ? "iso" : name().toLowerCase();
    }
}
