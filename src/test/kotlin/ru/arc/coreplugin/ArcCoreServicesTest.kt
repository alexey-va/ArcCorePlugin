package ru.arc.coreplugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcSqlService
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

            host.close()
            host.close()

            manager.load(ArcCoreServices::class.java) shouldBe null
            manager.load(ArcSqlService::class.java) shouldBe null
            manager.load(ArcRedisService::class.java) shouldBe null
            manager.load(ArcMetricsService::class.java) shouldBe null
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
