package ru.arc.coreplugin

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.metrics.PaperArcMetricsProvider
import ru.arc.paper.packet.PaperVisualPacketRuntime
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider
import ru.arc.paper.schematics.PaperSchematicServiceProvider
import ru.arc.paper.sql.PaperSqlServiceProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path
import java.util.logging.Logger

/**
 * The single lifecycle owner for ArcCore's shared Paper service providers.
 * Consumers depend on ArcCore and resolve shared API contracts through ServicesManager.
 * This holder also exposes the configuration and metrics bridges used by ARC.
 */
class ArcCoreServices private constructor(
    private val owner: Plugin,
    val sql: PaperSqlServiceProvider,
    val redis: PaperRedisServiceProvider,
    val metrics: PaperArcMetricsProvider,
    val visualPackets: PaperVisualPacketRuntime,
    val nativeCleanup: PaperNativeCleanupServiceProvider,
    private val schematics: PaperSchematicServiceProvider?,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val prepared = CompletableFuture.completedFuture<Void>(null)
    private var visualPolicy: VisualPacketServiceConfig? = null
    private var shutdown = CompletableFuture.completedFuture<Void>(null)

    /** Completion includes config workers, retained consumer work and physical transport close. */
    fun drained(): CompletableFuture<Void> = shutdown.copy()

    /** Providers and policy are ready when synchronous installation returns. */
    fun ready(): CompletableFuture<Void> = if (closed.get()) {
        CompletableFuture.failedFuture(IllegalStateException("ArcCore is closed"))
    } else prepared.copy()
    fun isReady(): Boolean = !closed.get()

    /** Manual maintenance: validate and apply on the owning thread before returning. */
    fun reloadVisualPacketConfigAsync(): CompletableFuture<Void> {
        check(Bukkit.isPrimaryThread()) { "Visual packet policy reload must start on the server thread" }
        return try {
            check(!closed.get()) { "ArcCore is closed" }
            visualPackets.updateSettings(checkNotNull(visualPolicy).prepareReload())
            CompletableFuture.completedFuture(null)
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture(failure)
        }
    }

    /** Binary-compatible entrypoint. New callers observe reloadVisualPacketConfigAsync. */
    fun reloadVisualPacketConfig() {
        reloadVisualPacketConfigAsync().whenComplete { _, failure ->
            if (failure != null) owner.logger.warning("ArcCore visual policy reload failed (${failure.javaClass.simpleName}); previous limits remain active")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        prepared.completeExceptionally(CancellationException("ArcCore is closed"))
        val manager = owner.server.servicesManager
        val failure = closeInReverse(
            listOfNotNull(
                sql,
                redis,
                metrics,
                visualPackets,
                nativeCleanup,
                schematics,
                AutoCloseable { manager.unregister(ArcMetricsService::class.java, metrics) },
                AutoCloseable { manager.unregister(ArcCoreServices::class.java, this) },
            ),
        )
        shutdown = CompletableFuture.allOf(sql.closeAsync(), redis.closeAsync())
        failure?.let { throw it }
    }

    companion object {
        /** Creates and publishes the complete host; any partial startup is cleaned up before failure escapes. */
        @JvmStatic
        fun install(owner: Plugin): ArcCoreServices = installConfigured(owner,
            HostConfiguration.load(owner.dataFolder.toPath(), owner.logger))

        @JvmStatic
        fun install(owner: Plugin, schematicSettings: ArcSchematicCacheSettings): ArcCoreServices =
            installConfigured(owner, HostConfiguration(schematicSettings,
                VisualPacketServiceConfig.load(owner.dataFolder.toPath(), owner.logger)))

        /** Compatibility for a caller that already owns a detached configuration preparation. */
        internal fun installPrepared(owner: Plugin, io: BoundedAsyncExecutor, configuration: CompletableFuture<HostConfiguration>): ArcCoreServices =
            try { installConfigured(owner, configuration.join()) } finally { io.close() }

        private fun installConfigured(owner: Plugin, configuration: HostConfiguration): ArcCoreServices {
            check(Bukkit.isPrimaryThread()) { "ArcCore installation must run on the server thread" }
            val manager = owner.server.servicesManager
            val providers = mutableListOf<AutoCloseable>()
            var metrics: PaperArcMetricsProvider? = null
            var holder: ArcCoreServices? = null

            try {
                check(manager.load(ArcMetricsService::class.java) == null) {
                    "An ArcMetricsService provider is already registered"
                }
                val sql = PaperSqlServiceProvider(owner).also(providers::add)
                val redis = PaperRedisServiceProvider(owner).also(providers::add)
                val createdMetrics = PaperArcMetricsProvider(null).also(providers::add)
                metrics = createdMetrics
                val visualPackets = PaperVisualPacketRuntime.installPending(owner).also(providers::add)
                val nativeCleanup = PaperNativeCleanupServiceProvider(owner).also(providers::add)
                val schematics = if (owner.server.pluginManager.isPluginEnabled("WorldEdit")) {
                    PaperSchematicServiceProvider(owner, CompletableFuture.completedFuture(configuration.schematics)).also(providers::add)
                } else {
                    owner.logger.info("Shared schematics unavailable: WorldEdit is not enabled")
                    null
                }
                visualPackets.updateSettings(configuration.visual.initialSettings)
                val createdHolder = ArcCoreServices(owner, sql, redis, createdMetrics, visualPackets, nativeCleanup, schematics)
                createdHolder.visualPolicy = configuration.visual
                holder = createdHolder

                manager.register(ArcCoreServices::class.java, createdHolder, owner, ServicePriority.Normal)
                manager.register(ArcMetricsService::class.java, createdMetrics, owner, ServicePriority.Normal)
                return createdHolder
            } catch (failure: Throwable) {
                val cleanup = providers.toMutableList()
                metrics?.let { cleanup += AutoCloseable { manager.unregister(ArcMetricsService::class.java, it) } }
                holder?.let { cleanup += AutoCloseable { manager.unregister(ArcCoreServices::class.java, it) } }
                closeInReverse(cleanup)?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

/** Closes every owned resource in reverse creation order and retains all failures. */
internal fun closeInReverse(resources: List<AutoCloseable>): Throwable? {
    var failure: Throwable? = null
    resources.asReversed().forEach { resource ->
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            val previousFailure = failure
            if (previousFailure == null) {
                failure = closeFailure
            } else {
                previousFailure.addSuppressed(closeFailure)
            }
        }
    }
    return failure
}

internal data class HostConfiguration(val schematics: ArcSchematicCacheSettings, val visual: VisualPacketServiceConfig) {
    companion object {
        fun load(root: Path, logger: Logger) = HostConfiguration(
            SchematicServiceConfig.loadSettings(root, logger), VisualPacketServiceConfig.load(root, logger),
        )
    }
}
