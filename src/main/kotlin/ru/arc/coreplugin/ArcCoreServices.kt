package ru.arc.coreplugin

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.metrics.PaperArcMetricsProvider
import ru.arc.paper.packet.PaperVisualPacketRuntime
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.schematics.PaperSchematicServiceProvider
import ru.arc.paper.sql.PaperSqlServiceProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val io: BoundedAsyncExecutor,
    private val scheduler: BukkitTaskScheduler,
    private val tasks: LifecycleTaskScope,
    private val schematics: PaperSchematicServiceProvider?,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val reloadRevision = AtomicLong()
    private val prepared = CompletableFuture<Void>()
    private var visualPolicy: VisualPacketServiceConfig? = null
    private var shutdown = CompletableFuture.completedFuture<Void>(null)

    /** Completion includes config workers, retained consumer work and physical transport close. */
    fun drained(): CompletableFuture<Void> = shutdown.copy()

    /** Observes policy preparation without blocking Paper; service identities already exist. */
    fun ready(): CompletableFuture<Void> = prepared.copy()
    fun isReady(): Boolean = prepared.isDone && !prepared.isCompletedExceptionally && !closed.get()

    private fun attach(candidate: CompletableFuture<HostConfiguration>) {
        candidate.whenComplete { config, failure ->
            if (closed.get()) return@whenComplete
            tasks.callSync {
                check(!closed.get()) { "ArcCore is closed" }
                if (failure != null) throw failure
                visualPackets.updateSettings(config.visual.initialSettings)
                visualPolicy = config.visual
            }.whenComplete { _, problem ->
                if (problem == null) prepared.complete(null) else prepared.completeExceptionally(problem)
            }
        }
    }

    /** Worker preparation, followed by an owning-thread swap of the validated host policy. */
    fun reloadVisualPacketConfigAsync(): CompletableFuture<Void> {
        check(Bukkit.isPrimaryThread()) { "Visual packet policy reload must start on the server thread" }
        if (!isReady()) return CompletableFuture.failedFuture(IllegalStateException("ArcCore is not ready"))
        val revision = reloadRevision.incrementAndGet()
        val result = CompletableFuture<Void>()
        val policy = checkNotNull(visualPolicy)
        io.submit { policy.prepareReload() }.thenCompose { candidate ->
            tasks.callSync {
                if (closed.get() || reloadRevision.get() != revision) throw CancellationException("ArcCore policy reload was superseded")
                visualPackets.updateSettings(candidate)
            }
        }.whenComplete { _, failure ->
            if (failure == null) result.complete(null) else result.completeExceptionally(failure)
        }
        return result
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
        tasks.close()
        scheduler.close()
        io.close()
        val manager = owner.server.servicesManager
        val failure = closeInReverse(
            listOfNotNull(
                sql,
                redis,
                metrics,
                visualPackets,
                schematics,
                AutoCloseable { manager.unregister(ArcMetricsService::class.java, metrics) },
                AutoCloseable { manager.unregister(ArcCoreServices::class.java, this) },
            ),
        )
        shutdown = CompletableFuture.allOf(io.closeAsync(), sql.closeAsync(), redis.closeAsync())
        failure?.let { throw it }
    }

    companion object {
        /** Creates and publishes the complete host; any partial startup is cleaned up before failure escapes. */
        @JvmStatic
        fun install(owner: Plugin): ArcCoreServices {
            val io = BoundedAsyncExecutor("arc-core-config", 1, 16)
            val root = owner.dataFolder.toPath()
            val logger = owner.logger
            return installPrepared(owner, io, io.submit { HostConfiguration.load(root, logger) })
        }

        @JvmStatic
        fun install(owner: Plugin, schematicSettings: ArcSchematicCacheSettings): ArcCoreServices {
            val io = BoundedAsyncExecutor("arc-core-config", 1, 16)
            val root = owner.dataFolder.toPath()
            val logger = owner.logger
            return installPrepared(owner, io, io.submit { HostConfiguration(schematicSettings, VisualPacketServiceConfig.load(root, logger)) })
        }

        internal fun installPrepared(owner: Plugin, io: BoundedAsyncExecutor, configuration: CompletableFuture<HostConfiguration>): ArcCoreServices {
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
                val schematics = if (owner.server.pluginManager.isPluginEnabled("WorldEdit")) {
                    PaperSchematicServiceProvider(owner, configuration.thenApply { it.schematics }).also(providers::add)
                } else {
                    owner.logger.info("Shared schematics unavailable: WorldEdit is not enabled")
                    null
                }
                val scheduler = BukkitTaskScheduler(owner)
                providers += scheduler
                val tasks = LifecycleTaskScope(scheduler)
                providers += tasks
                val createdHolder = ArcCoreServices(owner, sql, redis, createdMetrics, visualPackets, io, scheduler, tasks, schematics)
                holder = createdHolder

                manager.register(ArcCoreServices::class.java, createdHolder, owner, ServicePriority.Normal)
                manager.register(ArcMetricsService::class.java, createdMetrics, owner, ServicePriority.Normal)
                createdHolder.attach(configuration)
                return createdHolder
            } catch (failure: Throwable) {
                val cleanup = providers.toMutableList()
                metrics?.let { cleanup += AutoCloseable { manager.unregister(ArcMetricsService::class.java, it) } }
                holder?.let { cleanup += AutoCloseable { manager.unregister(ArcCoreServices::class.java, it) } }
                closeInReverse(cleanup)?.let(failure::addSuppressed)
                io.close()
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
