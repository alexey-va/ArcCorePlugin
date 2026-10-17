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
        var failure: Throwable? = null
        try { active.close() } catch (problem: Throwable) { failure = problem }
        // All native admission is closed above. These provider futures wait only for
        // accepted storage operations and retained consumer leases, never another tick.
        try { active.drained().join() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        failure?.let { logger.log(Level.SEVERE, "Failed to close ArcCore services", it) }
    }
}
