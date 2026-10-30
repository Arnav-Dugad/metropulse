package io.github.arnavdugad.metropulse.core.data.city

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.domain.city.CityDataPolicy
import io.github.arnavdugad.metropulse.core.domain.city.CityInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the per-city database files in `filesDir/cities/`. Each file starts as a copy of the
 * database bundled in the APK and may later be replaced by a newer download. Replacement happens
 * under [lock], which the database layer also holds while a city database is open.
 */
@Singleton
class CityFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val directory: File get() = File(context.filesDir, "cities").apply { mkdirs() }
    val lock = Mutex()

    private val _replaced = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a city id after its database file has been swapped for a newer version. */
    val replaced: SharedFlow<String> = _replaced.asSharedFlow()

    fun file(cityId: String): File = File(directory, "$cityId.db")

    /** Cities shipped inside this APK (works offline). */
    val bundled: List<CityInfo> by lazy {
        context.assets.open("cities/manifest.json").bufferedReader().use { CityManifest.parse(json, it.readText()) }
    }

    /** Copies the bundled database into place if there is none yet, or if the APK carries newer data. */
    suspend fun ensureInstalled(cityId: String): File = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = file(cityId)
            val bundledVersion = bundled.firstOrNull { it.id == cityId }?.version
                ?: if (target.exists()) return@withLock target else throw IOException("No data for $cityId")
            if (!target.exists() || (installedVersion(target) ?: -1) < bundledVersion) {
                val tmp = File(directory, "$cityId.db.copy")
                context.assets.open("cities/$cityId.db").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                replaceAtomically(tmp, target)
            }
            target
        }
    }

    /** Versions of every city database present on the device. */
    suspend fun installedVersions(): Map<String, Long> = withContext(Dispatchers.IO) {
        directory.listFiles { f -> f.name.endsWith(".db") }.orEmpty()
            .mapNotNull { f -> installedVersion(f)?.let { f.name.removeSuffix(".db") to it } }
            .toMap()
    }

    /**
     * Checks a downloaded database before it replaces the current one: it must open, pass SQLite's
     * integrity check, belong to [expected], and use the schema this app understands.
     */
    fun verifyDatabase(file: File, expected: CityInfo): Boolean = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val ok = db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            val meta = db.rawQuery("SELECT key, value FROM meta", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
            ok && meta["city_id"] == expected.id &&
                meta["schema_version"]?.toIntOrNull() == CityDataPolicy.SUPPORTED_SCHEMA &&
                meta["data_version"]?.toLongOrNull() == expected.version
        }
    }.getOrDefault(false)

    suspend fun install(downloaded: File, city: CityInfo) = withContext(Dispatchers.IO) {
        lock.withLock { replaceAtomically(downloaded, file(city.id)) }
        _replaced.emit(city.id)
    }

    private fun installedVersion(file: File): Long? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key = 'data_version'", null).use { c ->
                if (c.moveToFirst()) c.getString(0).toLongOrNull() else null
            }
        }
    }.getOrNull()

    private fun replaceAtomically(source: File, target: File) {
        // SQLite side files belong to the old database and must not be applied to the new one.
        listOf("-journal", "-wal", "-shm").forEach { File(target.path + it).delete() }
        if (!source.renameTo(target)) {
            target.delete()
            if (!source.renameTo(target)) throw IOException("Could not install ${target.name}")
        }
    }
}
