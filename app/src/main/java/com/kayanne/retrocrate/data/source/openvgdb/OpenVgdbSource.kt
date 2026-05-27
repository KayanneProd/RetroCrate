package com.kayanne.retrocrate.data.source.openvgdb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.kayanne.retrocrate.data.source.LibretroThumbnails
import com.kayanne.retrocrate.domain.model.Game
import com.kayanne.retrocrate.domain.model.Platform
import com.kayanne.retrocrate.domain.model.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

object OpenVgdbSource {

    private const val TAG = "OpenVgdb"
    private const val DB_FILENAME = "openvgdb.sqlite"
    private const val ZIP_ASSET = "openvgdb.zip"

    @Volatile private var db: SQLiteDatabase? = null
    private val initMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()

    suspend fun initialize(context: Context) {
        initMutex.withLock {
            if (db != null) return
            val target = File(context.filesDir, DB_FILENAME)
            if (!target.exists()) {
                Log.i(TAG, "Extracting OpenVGDB from assets to ${target.absolutePath}")
                extractFromAssets(context, target)
            }
            db = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    val SUPPORTED_PLATFORMS: List<Platform> =
        Platform.entries.filter { systemShortNameFor(it) != null }

    suspend fun fetchAllCatalogs(): List<Game> = withContext(Dispatchers.IO) {
        awaitReady()
        SUPPORTED_PLATFORMS.flatMap { platform -> fetchCatalog(platform) }
    }

    // Returns one Game per unique title on the platform. Regional releases of the same title
    // are MERGED — USA's title spelling wins, but description / genres / developer / publisher /
    // year are filled in from any region that has them (USA may have a blank description while
    // Europe filled it in; we keep both pieces).
    suspend fun fetchCatalog(platform: Platform): List<Game> = withContext(Dispatchers.IO) {
        awaitReady()
        val database = db ?: return@withContext emptyList()
        val systemShortName = systemShortNameFor(platform) ?: return@withContext emptyList()

        val sql = """
            SELECT R.releaseTitleName, R.releaseGenre, R.releaseDescription,
                   R.releaseDeveloper, R.releasePublisher, R.releaseDate, REG.regionName,
                   R.releaseCoverFront, M.romFileName
            FROM RELEASES R
            JOIN ROMs M ON R.romID = M.romID
            JOIN SYSTEMS S ON M.systemID = S.systemID
            LEFT JOIN REGIONS REG ON R.regionLocalizedID = REG.regionID
            WHERE S.systemShortName = ?
              AND R.releaseTitleName IS NOT NULL
        """.trimIndent()

        val rows = mutableListOf<RegionalRow>()
        database.rawQuery(sql, arrayOf(systemShortName)).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)?.trim() ?: continue
                if (title.isEmpty()) continue
                rows.add(
                    RegionalRow(
                        title = title,
                        genres = c.getString(1)
                            ?.split(';', ',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            .orEmpty(),
                        description = c.getString(2)?.takeIf { it.isNotBlank() },
                        developer = c.getString(3)?.takeIf { it.isNotBlank() },
                        publisher = c.getString(4)?.takeIf { it.isNotBlank() },
                        year = c.getString(5)?.take(4)?.toIntOrNull()?.takeIf { it in 1970..2030 },
                        region = c.getString(6),
                        coverUrl = c.getString(7)?.takeIf { it.isNotBlank() },
                        romFileName = c.getString(8)?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }

        val merged = rows
            .groupBy { it.title.lowercase().trim() }
            .map { (_, regional) -> mergeRegionalRows(regional) }
            .sortedBy { it.title.lowercase() }

        val hits = merged.count { it.description != null || it.genres.isNotEmpty() }
        Log.i(TAG, "OpenVGDB $platform: ${merged.size} titles, $hits with description or genre")

        merged.map { row ->
            Game(
                id = "openvgdb:${platform.name.lowercase()}:${row.title.toSlug()}",
                title = row.title,
                platform = platform,
                releaseYear = row.year,
                developer = row.developer,
                publisher = row.publisher,
                genres = row.genres,
                description = row.description,
                // Prefer OpenVGDB's curated cover URL (90%+ coverage), fall back to
                // libretro-thumbnails (which often 404s for less common titles).
                boxArtUrl = row.coverUrl ?: LibretroThumbnails.boxArtUrl(platform, row.title),
                heroArtUrl = LibretroThumbnails.snapUrl(platform, row.title),
                sources = if (row.romFileName != null) {
                    listOf(
                        Source(
                            id = "openvgdb-rom:${row.romFileName}",
                            siteName = "Internet Archive",
                            region = row.region,
                            sizeBytes = null,
                            resolveUrl = row.romFileName, // No-Intro filename, used by IaResolver
                        ),
                    )
                } else emptyList(),
            )
        }
    }

    private data class RegionalRow(
        val title: String,
        val genres: List<String>,
        val description: String?,
        val developer: String?,
        val publisher: String?,
        val year: Int?,
        val region: String?,
        val coverUrl: String?,
        val romFileName: String?,
    )

    private fun mergeRegionalRows(rows: List<RegionalRow>): RegionalRow {
        val sorted = rows.sortedBy { regionRank(it.region) }
        val primary = sorted.first()
        return primary.copy(
            description = primary.description ?: sorted.firstNotNullOfOrNull { it.description },
            developer = primary.developer ?: sorted.firstNotNullOfOrNull { it.developer },
            publisher = primary.publisher ?: sorted.firstNotNullOfOrNull { it.publisher },
            year = primary.year ?: sorted.firstNotNullOfOrNull { it.year },
            genres = primary.genres.ifEmpty {
                sorted.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty()
            },
            coverUrl = primary.coverUrl ?: sorted.firstNotNullOfOrNull { it.coverUrl },
            romFileName = primary.romFileName ?: sorted.firstNotNullOfOrNull { it.romFileName },
        )
    }

    private fun regionRank(region: String?): Int = when (region?.lowercase()) {
        "usa" -> 0
        "world" -> 1
        "usa, europe" -> 2
        "europe" -> 3
        "japan" -> 4
        else -> 99
    }

    private suspend fun extractFromAssets(context: Context, target: File) =
        withContext(Dispatchers.IO) {
            context.assets.open(ZIP_ASSET).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val isSqlite = name.endsWith(".sqlite") &&
                            !name.startsWith("__MACOSX") &&
                            !name.contains("/._")
                        if (isSqlite) {
                            target.outputStream().use { output -> zip.copyTo(output) }
                            return@withContext
                        }
                        entry = zip.nextEntry
                    }
                    error("openvgdb.zip did not contain a .sqlite entry")
                }
            }
        }

    // Mappings verified against OpenVGDB v29.0 SYSTEMS table. Dreamcast and PS2 are NOT in
    // OpenVGDB — those platforms get null and drop out of SUPPORTED_PLATFORMS.
    private fun systemShortNameFor(platform: Platform): String? = when (platform) {
        Platform.NES -> "NES"
        Platform.SNES -> "SNES"
        Platform.N64 -> "N64"
        Platform.GAMECUBE -> "NGC"
        Platform.WII -> "Wii"
        Platform.GAME_BOY -> "GB"
        Platform.GAME_BOY_COLOR -> "GBC"
        Platform.GAME_BOY_ADVANCE -> "GBA"
        Platform.NINTENDO_DS -> "NDS"
        Platform.GENESIS -> "MD"
        Platform.SATURN -> "Saturn"
        Platform.PS1 -> "PSX"
        Platform.PSP -> "PSP"
        else -> null
    }

    private fun String.toSlug(): String =
        lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
