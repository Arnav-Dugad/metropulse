package io.github.arnavdugad.metropulse.core.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.arnavdugad.metropulse.core.domain.update.SemVer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdatePreferences @Inject constructor(private val store: DataStore<Preferences>) {

    val autoCheck: Flow<Boolean> = store.data.map { it[AUTO_CHECK] ?: true }
    val lastCheckedAt: Flow<Long?> = store.data.map { it[LAST_CHECKED_AT] }

    suspend fun setAutoCheck(enabled: Boolean) {
        store.edit { it[AUTO_CHECK] = enabled }
    }

    suspend fun skippedVersion(): SemVer? = store.data.first()[SKIPPED_VERSION]?.let(SemVer::parse)
    suspend fun skip(version: SemVer) {
        store.edit { it[SKIPPED_VERSION] = version.toString() }
    }

    suspend fun markChecked(at: Long) {
        store.edit { it[LAST_CHECKED_AT] = at }
    }

    /** Returns true the first time it is called for [version], so each release is announced once. */
    suspend fun claimNotification(version: SemVer): Boolean = claim(LAST_NOTIFIED_VERSION, version)

    /** Like [claimNotification], for the in-app sheet shown on launch. */
    suspend fun claimPrompt(version: SemVer): Boolean = claim(LAST_PROMPTED_VERSION, version)

    private suspend fun claim(key: Preferences.Key<String>, version: SemVer): Boolean {
        var claimed = false
        store.edit {
            if (it[key] != version.toString()) {
                it[key] = version.toString()
                claimed = true
            }
        }
        return claimed
    }

    private companion object {
        val AUTO_CHECK = booleanPreferencesKey("update_auto_check")
        val SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")
        val LAST_CHECKED_AT = longPreferencesKey("update_last_checked_at")
        val LAST_NOTIFIED_VERSION = stringPreferencesKey("update_last_notified_version")
        val LAST_PROMPTED_VERSION = stringPreferencesKey("update_last_prompted_version")
    }
}
