package io.github.arnavdugad.metropulse.core.domain.update

sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision
    data class Available(val release: AppRelease) : UpdateDecision
    /** A newer release exists but the user chose to skip exactly this version. */
    data class Skipped(val release: AppRelease) : UpdateDecision
}

object UpdatePolicy {
    /**
     * Decides whether [latest] should be offered to a user running [installed].
     * Pre-releases are never offered to users on a stable build.
     */
    fun decide(installed: SemVer, latest: AppRelease?, skippedVersion: SemVer?): UpdateDecision {
        if (latest == null || latest.version <= installed) return UpdateDecision.UpToDate
        if (latest.version.isPreRelease && !installed.isPreRelease) return UpdateDecision.UpToDate
        if (skippedVersion != null && skippedVersion == latest.version) return UpdateDecision.Skipped(latest)
        return UpdateDecision.Available(latest)
    }
}
