package ru.arc.coreplugin

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.api.ArcSchematicCacheSettings
import java.util.logging.Level

open class ArcCorePlugin : JavaPlugin() {
    private var services: ArcCoreServices? = null
    private var schematicSettings = ArcSchematicCacheSettings.defaults()

    override fun onLoad() {
        schematicSettings = SchematicServiceConfig.loadSettings(dataFolder.toPath(), logger)
    }

    override fun onEnable() {
        services = ArcCoreServices.install(this, schematicSettings)
    }

    override fun onDisable() {
        val active = services ?: return
        services = null
        try {
            active.close()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Failed to close ArcCore services", failure)
        }
    }
}
