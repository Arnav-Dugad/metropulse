package io.github.arnavdugad.metropulse.core.data

/** Build facts the data layer needs but cannot read itself (BuildConfig lives in :app). */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
    /** Releases endpoint returning a GitHub "release" JSON object. Blank when updates are off. */
    val updateFeedUrl: String,
    val updatesEnabled: Boolean,
    /** data/manifest.json in the repository, listing the latest city databases. */
    val dataManifestUrl: String = "",
    /** New York live predictions straight from the MTA's servers; off unless the MTA permits it. */
    val mtaRealtime: Boolean = false,
    /** Key for Delhi's Open Transit Data bus positions; blank leaves Delhi's live buses off. */
    val otdDelhiKey: String = "",
) {
    val userAgent: String get() = "MetroPulse/$versionName (Android; +https://github.com/Arnav-Dugad/metropulse)"
}
