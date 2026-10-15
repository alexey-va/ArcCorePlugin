package ru.arc.coreplugin

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.metrics.PaperArcMetricsProvider
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.sql.PaperSqlServiceProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single lifecycle owner for ArcCore's shared Paper service providers.
 * Consumers must depend on ArcCore and resolve this holder through ServicesManager.
 */
class ArcCoreServices private constructor(
    private val owner: Plugin,
    val sql: PaperSqlServiceProvider,
    val redis: PaperRedisServiceProvider,
    val metrics: PaperArcMetricsProvider,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val manager = owner.server.servicesManager
        val failure = closeInReverse(
            listOf(
                sql,
                redis,
                metrics,
                AutoCloseable { manager.unregister(ArcMetricsService::class.java, metrics) },
                AutoCloseable { manager.unregister(ArcCoreServices::class.java, this) },
            ),
        )
        failure?.let { throw it }
    }

    companion object {
        /** Creates and publishes the complete host; any partial startup is cleaned up before failure escapes. */
        @JvmStatic
        fun install(owner: Plugin): ArcCoreServices {
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
                val createdHolder = ArcCoreServices(owner, sql, redis, createdMetrics)
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
