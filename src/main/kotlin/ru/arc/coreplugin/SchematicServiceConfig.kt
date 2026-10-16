package ru.arc.coreplugin

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.paper.api.ArcSchematicCacheSettings
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** Host-wide resource limits. Library roots remain explicit consumer-owned configuration. */
internal open class SchematicServiceConfig(private val config: Config) {
    private val defaults = ArcSchematicCacheSettings.defaults()

    open val ttlMillis: Long get() = config.durationMillis("cache.ttl", defaults.ttlMillis)
    open val maximumEntries: Int get() = config.int("cache.maximum-entries", defaults.maximumEntries)
    open val maximumCachedBytes: Long get() = config.long("cache.maximum-bytes", defaults.maximumCachedBytes)
    open val maximumFileBytes: Int get() = config.int("limits.maximum-file-bytes", defaults.maximumFileBytes)
    open val maximumInflatedBytes: Int get() = config.int("limits.maximum-inflated-bytes", defaults.maximumInflatedBytes)
    open val maximumVolume: Int get() = config.int("limits.maximum-volume", defaults.maximumVolume)
    open val maximumPendingRequests: Int get() = config.int("workers.maximum-pending-requests", defaults.maximumPendingRequests)
    open val workerThreads: Int get() = config.int("workers.threads", defaults.workerThreads)
    open val palettePerTick: Int get() = config.int("paper.palette-per-tick", defaults.palettePerTick)
    open val blocksPerTick: Int get() = config.int("paper.blocks-per-tick", defaults.blocksPerTick)

    fun snapshot() = ArcSchematicCacheSettings(
        ttlMillis,
        maximumEntries,
        maximumCachedBytes,
        maximumFileBytes,
        maximumInflatedBytes,
        maximumVolume,
        maximumPendingRequests,
        workerThreads,
        palettePerTick,
        blocksPerTick,
    )

    companion object {
        fun loadSettings(dataPath: Path, logger: Logger): ArcSchematicCacheSettings = try {
            load(dataPath).snapshot()
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Could not load schematics.yml; using built-in resource limits", failure)
            ArcSchematicCacheSettings.defaults()
        }

        fun load(dataPath: Path): SchematicServiceConfig {
            val config = ConfigManager.of(dataPath, "schematics.yml")
            config.mergeMissingFromBundled("schematics.yml")
            return SchematicServiceConfig(config)
        }
    }
}
