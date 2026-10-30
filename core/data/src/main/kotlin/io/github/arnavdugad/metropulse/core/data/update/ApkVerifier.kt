package io.github.arnavdugad.metropulse.core.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

sealed interface ApkVerification {
    data object Valid : ApkVerification
    data class Invalid(val reason: String) : ApkVerification
}

/**
 * Checks a downloaded APK before it is handed to the system installer: it must be this app, a
 * newer build, and signed with the same certificate as the installed copy. Android enforces the
 * signature rule itself, but checking first gives a clear error instead of a failed install.
 */
class ApkVerifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun verify(apk: File): ApkVerification {
        val pm = context.packageManager
        val archive = pm.archiveInfo(apk) ?: return ApkVerification.Invalid("The update file is not a valid app package")
        val installed = pm.getPackageInfo(context.packageName, signingFlags())

        if (archive.packageName != context.packageName) {
            return ApkVerification.Invalid("The update is for a different app (${archive.packageName})")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return ApkVerification.Invalid("The update is not newer than the installed version")
        }
        val archiveCerts = archive.certificateDigests()
        val installedCerts = installed.certificateDigests()
        return when {
            // Some API 26-27 builds cannot read signatures from an archive; the installer still enforces them.
            archiveCerts.isEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> ApkVerification.Valid
            archiveCerts.isEmpty() -> ApkVerification.Invalid("The update is not signed")
            // containsAll rather than equality so a future key rotation (lineage A -> A,B) still verifies.
            !archiveCerts.containsAll(installedCerts) -> ApkVerification.Invalid("The update is not signed by the MetroPulse developer")
            else -> ApkVerification.Valid
        }
    }

    private fun PackageManager.archiveInfo(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(apk.path, signingFlags())
        }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    private fun PackageInfo.certificateDigests(): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION")
            signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
