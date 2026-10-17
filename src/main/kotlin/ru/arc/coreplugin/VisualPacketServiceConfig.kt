package ru.arc.coreplugin

import org.bukkit.plugin.Plugin
import ru.arc.config.Config
import ru.arc.paper.packet.PaperVisualPacketRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/** Host policy; the legacy ARC file is a one-time migration source, never a second live owner. */
internal class VisualPacketServiceConfig private constructor(private val config: Config) {
    val initialSettings: PaperVisualPacketRuntime.Settings

    init {
        // Config represents malformed/unreadable YAML as an empty map. Never replace
        // an existing operator policy with defaults when it could not be read.
        require(KEYS.any(config::exists)) { "No visual packet settings loaded from ArcCore/visual-packets.yml" }
        initialSettings = PaperVisualPacketRuntime.Settings.from(config)
    }

    fun install(owner: Plugin): PaperVisualPacketRuntime = PaperVisualPacketRuntime.install(owner, config)

    fun reload(runtime: PaperVisualPacketRuntime) {
        config.reload()
        runtime.reload(config)
    }

    companion object {
        private val KEYS = listOf("server-mbps", "viewer-mbps", "burst-ms", "log-interval-seconds", "verbose-logs")

        fun load(dataPath: Path, logger: Logger): VisualPacketServiceConfig {
            val file = dataPath.resolve(PaperVisualPacketRuntime.CONFIG_FILE)
            if (!Files.exists(file)) {
                Files.createDirectories(dataPath)
                val legacy = dataPath.resolveSibling("ARC").resolve("modules").resolve(PaperVisualPacketRuntime.CONFIG_FILE)
                if (Files.exists(legacy)) {
                    Files.copy(legacy, file)
                    logger.info("Imported visual packet policy from $legacy to $file; future reloads use the ArcCore file")
                } else {
                    checkNotNull(VisualPacketServiceConfig::class.java.getResourceAsStream("/visual-packets.yml")) {
                        "Bundled visual-packets.yml is unavailable"
                    }.use { Files.copy(it, file) }
                }
            }
            return VisualPacketServiceConfig(Config(dataPath, PaperVisualPacketRuntime.CONFIG_FILE))
        }
    }
}
