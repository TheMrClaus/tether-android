package com.tether.app.mirror

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * T13.1: the per-server-origin journal mirror (SYNC_DESIGN §2.2). A CACHE: deletable at any
 * time and rebuilt from the server. Unsent input never lives here (it stays in DataStore), so
 * nothing in this file can ever destroy user data.
 *
 * Schemas are exported to `core/data/schemas/` (§8.4); T13.5 owns migrations. Until then any
 * version step falls back to a destructive rebuild, which is allowed for the mirror only.
 */
@Database(
    entities = [
        SessionRowEntity::class,
        SessionBaseEntity::class,
        JournalEventEntity::class,
        TurnDetailEntity::class,
        SyncStateEntity::class,
        MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MirrorDatabase : RoomDatabase() {
    abstract fun dao(): MirrorDao
}

/** Opens, lists and deletes mirror DB files. The production one uses the app's `databases/` dir. */
interface MirrorDbFactory {
    fun open(name: String): MirrorDatabase

    /** Names of the mirror DB files that exist (`mirror-*.db`). */
    fun existing(): List<String>

    /** Delete [name] and its `-wal` / `-shm` / `-journal` siblings. */
    fun delete(name: String)
}

/**
 * `databases/` is excluded from cloud backup and device transfer (data_extraction_rules.xml
 * and backup_rules.xml, domain `database`), so the mirror never leaves the device.
 */
class AndroidMirrorDbFactory(private val context: Context) : MirrorDbFactory {
    override fun open(name: String): MirrorDatabase =
        Room.databaseBuilder(context.applicationContext, MirrorDatabase::class.java, name)
            // Mirror only (§8.4): the data is a cache; unsent input is never in Room.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    override fun existing(): List<String> {
        val dir = context.getDatabasePath(PROBE_NAME).parentFile ?: return emptyList()
        return dir.listFiles { f -> f.isFile && MirrorFiles.isMirrorDb(f.name) }?.map { it.name }.orEmpty()
    }

    override fun delete(name: String) {
        val file = context.getDatabasePath(name)
        MirrorFiles.deleteWithSiblings(file)
    }

    private companion object {
        const val PROBE_NAME = "mirror-probe.db"
    }
}

object MirrorFiles {
    private val DB_NAME = Regex("^mirror-[0-9a-f]{16}\\.db$")

    fun isMirrorDb(name: String): Boolean = DB_NAME.matches(name)

    fun deleteWithSiblings(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val f = File(file.path + suffix)
            if (f.exists()) f.delete()
        }
    }
}
