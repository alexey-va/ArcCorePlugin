package ru.arc.coreplugin

import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

open class ArcCorePlugin : JavaPlugin() {
    private var services: ArcCoreServices? = null

    override fun onEnable() {
        services = ArcCoreServices.install(this)
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
