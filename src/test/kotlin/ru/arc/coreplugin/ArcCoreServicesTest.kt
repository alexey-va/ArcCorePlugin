package ru.arc.coreplugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSchematicService
import ru.arc.paper.api.ArcSceneObservationService
import ru.arc.paper.api.ArcSceneEntityInfoService
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

class ArcCoreServicesTest : StringSpec({
    "installation publishes ready policy before the next plugin can enable" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("SequentialArcCore")
            ArcCoreServices.install(owner).use { host ->
                host.ready().isDone shouldBe true
                host.isReady() shouldBe true
                paper.server.servicesManager.load(ArcSqlService::class.java) shouldNotBe null
                host.visualPackets.acquire("test:starting", Any(), 64, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            }
        }
    }

    "publishes the shared services and unregisters them when the host closes" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcCore")
            val manager = paper.server.servicesManager
            val host = ArcCoreServices.install(owner)
            awaitHost(paper, host.ready())

            manager.load(ArcCoreServices::class.java) shouldBe host
            manager.load(ArcSqlService::class.java) shouldNotBe null
            manager.load(ArcRedisService::class.java) shouldNotBe null
            manager.load(ArcMetricsService::class.java) shouldBe host.metrics
            manager.load(ArcVisualPacketBudget::class.java) shouldBe host.visualPackets
            manager.load(ArcNativeCleanupService::class.java) shouldBe host.nativeCleanup
            manager.load(ArcSceneObservationService::class.java) shouldNotBe null
            manager.load(ArcSceneEntityInfoService::class.java) shouldNotBe null
            manager.load(ArcSchematicService::class.java) shouldBe null
            paper.server.pluginManager.getPlugin("ARC") shouldBe null
            host.visualPackets.acquire("arccivs:preview", Any(), 64, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            host.visualPackets.recordSent("arccivs:preview", 64, 1, false)
            host.visualPackets.metricPoints().single { it.name == "arc_visual_packet_payload_bytes_total" }.value shouldBe 64.0

            host.close()
            host.close()

            manager.load(ArcCoreServices::class.java) shouldBe null
            manager.load(ArcSqlService::class.java) shouldBe null
            manager.load(ArcRedisService::class.java) shouldBe null
            manager.load(ArcMetricsService::class.java) shouldBe null
            manager.load(ArcVisualPacketBudget::class.java) shouldBe null
            manager.load(ArcNativeCleanupService::class.java) shouldBe null
            manager.load(ArcSceneObservationService::class.java) shouldBe null
            manager.load(ArcSceneEntityInfoService::class.java) shouldBe null
            manager.load(ArcSchematicService::class.java) shouldBe null
            host.visualPackets.snapshot().closed shouldBe true
            paper.server.scheduler.pendingTasks.count { it.owner === owner } shouldBe 0
        }
    }

    "publishes the optional schematic service without preloading files and closes it" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("WorldEdit")
            val owner = paper.createSimplePlugin("ArcCore")
            val manager = paper.server.servicesManager
            val host = ArcCoreServices.install(owner)
            awaitHost(paper, host.ready())
            val schematics = checkNotNull(manager.load(ArcSchematicService::class.java))

            manager.load(ArcSchematicService::class.java) shouldNotBe null
            schematics.diagnostics().entries shouldBe 0
            schematics.diagnostics().cachedBytes shouldBe 0L
            schematics.diagnostics().pendingRequests shouldBe 0
            schematics.diagnostics().activeLibraries shouldBe 0

            host.close()
            host.close()

            manager.load(ArcSchematicService::class.java) shouldBe null
            manager.load(ArcCoreServices::class.java) shouldBe null
            manager.load(ArcSqlService::class.java) shouldBe null
            manager.load(ArcRedisService::class.java) shouldBe null
            manager.load(ArcMetricsService::class.java) shouldBe null
            manager.load(ArcVisualPacketBudget::class.java) shouldBe null
            schematics.diagnostics().closed shouldBe true
        }
    }

    "rolls back earlier providers when the optional schematic service cannot register" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("WorldEdit")
            val owner = paper.createSimplePlugin("ArcCore")
            val manager = paper.server.servicesManager
            val existing = mockk<ArcSchematicService>()
            manager.register(ArcSchematicService::class.java, existing, owner, ServicePriority.Normal)

            shouldThrow<IllegalStateException> { ArcCoreServices.install(owner) }

            manager.load(ArcSchematicService::class.java) shouldBe existing
            manager.load(ArcCoreServices::class.java) shouldBe null
            manager.load(ArcSqlService::class.java) shouldBe null
            manager.load(ArcRedisService::class.java) shouldBe null
            manager.load(ArcMetricsService::class.java) shouldBe null
            manager.load(ArcVisualPacketBudget::class.java) shouldBe null
            paper.server.scheduler.pendingTasks.count { it.owner === owner } shouldBe 0
            manager.load(ArcSceneObservationService::class.java) shouldBe null
            manager.load(ArcSceneEntityInfoService::class.java) shouldBe null
            manager.unregister(ArcSchematicService::class.java, existing)
        }
    }

    "disabling ARC leaves the single host budget available for standalone consumers" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcCore")
            val consumer = paper.createSimplePlugin("ARC")
            val independentConsumer = paper.createSimplePlugin("ArcCivs")
            ArcCoreServices.install(owner).use { host ->
                awaitHost(paper, host.ready())
                val budget = independentConsumer.server.servicesManager.load(ArcVisualPacketBudget::class.java)
                budget shouldBe host.visualPackets
                paper.server.pluginManager.disablePlugin(consumer)
                paper.server.servicesManager.unregisterAll(consumer)

                independentConsumer.server.servicesManager.load(ArcVisualPacketBudget::class.java) shouldBe budget
                host.visualPackets.snapshot().closed shouldBe false
                budget!!.acquire("arccivs:preview", Any(), 1, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
            }
        }
    }

    "rejects an existing visual provider and rolls back only this startup" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcCore")
            val previousOwner = paper.createSimplePlugin("OldOwner")
            val manager = paper.server.servicesManager
            val existing = mockk<ArcVisualPacketBudget>()
            manager.register(ArcVisualPacketBudget::class.java, existing, previousOwner, ServicePriority.Normal)

            shouldThrow<IllegalStateException> { ArcCoreServices.install(owner) }

            manager.load(ArcVisualPacketBudget::class.java) shouldBe existing
            manager.load(ArcCoreServices::class.java) shouldBe null
            manager.load(ArcSqlService::class.java) shouldBe null
            manager.load(ArcRedisService::class.java) shouldBe null
            manager.load(ArcMetricsService::class.java) shouldBe null
            paper.server.scheduler.pendingTasks.count { it.owner === owner } shouldBe 0
        }
    }

    "native cleanup survives consumer disable and host stop reports unfinished work" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcCore")
            val consumer = paper.createSimplePlugin("CleanupConsumer")
            val host = ArcCoreServices.install(owner)
            try {
                val service = checkNotNull(paper.server.servicesManager.load(ArcNativeCleanupService::class.java))
                var calls = 0
                val cleanup = service.submit("consumer:entities") { calls++; calls == 1024 }.toCompletableFuture()
                paper.server.pluginManager.disablePlugin(consumer)
                paper.performTicks(1)
                (calls in 1..512) shouldBe true
                cleanup.isDone shouldBe false
                host.close()
                cleanup.isCompletedExceptionally shouldBe true
                paper.server.servicesManager.load(ArcNativeCleanupService::class.java) shouldBe null
                val before = calls
                paper.performTicks(3)
                calls shouldBe before
            } finally { host.close() }
        }
    }

    "reload reads host policy and invalid or missing files preserve active limits" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcCore")
            ArcCoreServices.install(owner).use { host ->
                awaitHost(paper, host.ready())
                val policy = owner.dataFolder.toPath().resolve("visual-packets.yml")
                Files.writeString(policy, "server-mbps: 12.0\nviewer-mbps: 0.5\nverbose-logs: false\n")
                awaitHost(paper, host.reloadVisualPacketConfigAsync())
                val expected = host.visualPackets.snapshot().settings
                expected.serverMbps shouldBe 12.0
                expected.viewerMbps shouldBe 0.5
                expected.verboseLogs shouldBe false

                listOf("", "viewer-mbps: -2.0\n", "viewer-mbps: [\n").forEach { broken ->
                    Files.writeString(policy, broken)
                    shouldThrow<ExecutionException> { awaitHost(paper, host.reloadVisualPacketConfigAsync()) }
                    host.visualPackets.snapshot().settings shouldBe expected
                    Files.readString(policy) shouldBe broken
                }
                Files.delete(policy)
                shouldThrow<ExecutionException> { awaitHost(paper, host.reloadVisualPacketConfigAsync()) }
                host.visualPackets.snapshot().settings shouldBe expected
                Files.exists(policy) shouldBe false
            }
        }
    }

    "continues closing remaining resources after a close failure" {
        val closed = mutableListOf<String>()
        val failure = IllegalStateException("close failed")
        val result = closeInReverse(
            listOf(
                AutoCloseable { closed += "first" },
                AutoCloseable { closed += "failing"; throw failure },
                AutoCloseable { closed += "last" },
            ),
        )

        result shouldBe failure
        closed.shouldContainExactly("last", "failing", "first")
    }
})

private fun <T> awaitHost(paper: MockBukkitTestRuntime, future: CompletableFuture<T>): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!future.isDone && System.nanoTime() < deadline) {
        paper.performTicks(1)
        Thread.yield()
    }
    return future.get(1, TimeUnit.SECONDS)
}
