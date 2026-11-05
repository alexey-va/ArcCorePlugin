package ru.arc.coreplugin

import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

open class ArcCorePlugin : JavaPlugin() {
    private var services: ArcCoreServices? = null
    private var legacyDialogs: AutoCloseable? = null

    override fun onEnable() {
        services = ArcCoreServices.install(this)
        val viaBackwards = server.pluginManager.getPlugin("ViaBackwards")
        if (viaBackwards?.isEnabled == true && server.pluginManager.isPluginEnabled("ViaVersion")) {
            if (viaBackwards.pluginMeta.version != "5.12.1") {
                logger.warning("Legacy dialog compatibility requires verified ViaBackwards 5.12.1; found ${viaBackwards.pluginMeta.version}")
            } else {
                try {
                    legacyDialogs = ViaBackwardsDialogCompatibility.install(this)
                } catch (failure: LinkageError) {
                    logger.log(Level.WARNING, "Could not install legacy dialog compatibility; verify ViaVersion and ViaBackwards 5.12.1", failure)
                } catch (failure: Exception) {
                    logger.log(Level.WARNING, "Could not install legacy dialog compatibility; older clients may be unable to close dialogs", failure)
                }
            }
        }
    }

    override fun onDisable() {
        try { legacyDialogs?.close() }
        catch (failure: Throwable) { logger.log(Level.WARNING, "Failed to restore ViaBackwards dialog provider", failure) }
        finally { legacyDialogs = null }
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
