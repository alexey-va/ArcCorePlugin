package ru.arc.coreplugin

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BoundedAsyncExecutor
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcCorePlugin : JavaPlugin() {
    private var services: ArcCoreServices? = null
    private var io: BoundedAsyncExecutor? = null
    private var predecessor = CompletableFuture.completedFuture<Void>(null)
    private var configuration: CompletableFuture<HostConfiguration>? = null

    override fun onLoad() = prepareConfiguration()

    private fun prepareConfiguration() {
        val worker = BoundedAsyncExecutor("arc-core-config", 1, 16)
        io = worker
        val root = dataFolder.toPath()
        val log = logger
        configuration = predecessor.thenCompose { worker.submit { HostConfiguration.load(root, log) } }
    }

    override fun onEnable() {
        if (io == null) prepareConfiguration()
        val candidate = checkNotNull(configuration) { "ArcCore configuration preparation did not start" }
        services = ArcCoreServices.installPrepared(this, checkNotNull(io), candidate)
        services!!.ready().whenComplete { _, failure ->
            if (failure != null) {
                logger.log(Level.SEVERE, "Failed to prepare ArcCore configuration", failure)
                if (org.bukkit.Bukkit.isPrimaryThread() && isEnabled) server.pluginManager.disablePlugin(this)
            }
        }
    }

    override fun onDisable() {
        val active = services
        val worker = io
        io = null
        configuration = null
        if (active == null) {
            if (worker != null) predecessor = worker.closeAsync().thenApply { null }
            return
        }
        services = null
        try {
            active.close()
            predecessor = active.drained()
        } catch (failure: Throwable) {
            predecessor = active.drained()
            logger.log(Level.SEVERE, "Failed to close ArcCore services", failure)
        }
    }
}
