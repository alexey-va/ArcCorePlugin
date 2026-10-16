package ru.arc.coreplugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSchematicService
import ru.arc.paper.testing.MockBukkitTestRuntime

class ArcCoreServicesTest : StringSpec({
    "publishes the shared services and unregisters them when the host closes" {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcCore")
            val manager = paper.server.servicesManager
            val host = ArcCoreServices.install(owner)

            manager.load(ArcCoreServices::class.java) shouldBe host
            manager.load(ArcSqlService::class.java) shouldNotBe null
            manager.load(ArcRedisService::class.java) shouldNotBe null
            manager.load(ArcMetricsService::class.java) shouldBe host.metrics
            manager.load(ArcSchematicService::class.java) shouldBe null

            host.close()
            host.close()

            manager.load(ArcCoreServices::class.java) shouldBe null
            manager.load(ArcSqlService::class.java) shouldBe null
            manager.load(ArcRedisService::class.java) shouldBe null
            manager.load(ArcMetricsService::class.java) shouldBe null
            manager.load(ArcSchematicService::class.java) shouldBe null
        }
    }

    "publishes the optional schematic service without preloading files and closes it" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("WorldEdit")
            val owner = paper.createSimplePlugin("ArcCore")
            val manager = paper.server.servicesManager
            val host = ArcCoreServices.install(owner)
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
            manager.unregister(ArcSchematicService::class.java, existing)
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
