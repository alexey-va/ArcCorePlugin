package ru.arc.coreplugin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.packet.PaperVisualPacketRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

class VisualPacketServiceConfigTest : StringSpec({
    "standalone host seeds the existing deployed policy without ARC" {
        withPolicyRoot { root ->
            val host = root.resolve("ArcCore")
            VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger()).initialSettings shouldBe
                PaperVisualPacketRuntime.Settings(verboseLogs = true)
            Files.exists(root.resolve("ARC")) shouldBe false
            Files.exists(host.resolve("visual-packets.yml")) shouldBe true
        }
    }

    "first startup imports exact legacy bytes once and leaves the source untouched" {
        withPolicyRoot { root ->
            val legacy = root.resolve("ARC/modules/visual-packets.yml")
            Files.createDirectories(legacy.parent)
            val original = "# existing policy\nserver-mbps: 7.5\nviewer-mbps: 0.25\nburst-ms: 90\nverbose-logs: false\n"
            Files.writeString(legacy, original)
            val host = root.resolve("ArcCore")
            val loaded = VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger()).initialSettings
            loaded.serverMbps shouldBe 7.5
            loaded.viewerMbps shouldBe 0.25
            loaded.burstMillis shouldBe 90L
            loaded.verboseLogs shouldBe false
            Files.readString(host.resolve("visual-packets.yml")) shouldBe original
            Files.readString(legacy) shouldBe original

            Files.writeString(legacy, "server-mbps: 999.0\n")
            VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger()).initialSettings shouldBe loaded
            Files.readString(host.resolve("visual-packets.yml")) shouldBe original
        }
    }

    "explicit host policy wins over an existing legacy policy without merging or rewriting" {
        withPolicyRoot { root ->
            val host = root.resolve("ArcCore")
            Files.createDirectories(host)
            val explicit = "# intentionally partial\nserver-mbps: 9.0\noperator-note: keep\n"
            Files.writeString(host.resolve("visual-packets.yml"), explicit)
            val legacy = root.resolve("ARC/modules/visual-packets.yml")
            Files.createDirectories(legacy.parent)
            Files.writeString(legacy, "invalid: [\n")

            VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger()).initialSettings.serverMbps shouldBe 9.0
            Files.readString(host.resolve("visual-packets.yml")) shouldBe explicit
        }
    }

    "source-only host exceptions preserve the ordinary default and reload from the canonical file" {
        withPolicyRoot { root ->
            val host = root.resolve("ArcCore")
            Files.createDirectories(host)
            val target = host.resolve("visual-packets.yml")
            val original = "source-viewer-mbps:\n  'game:video.wall': 24.0\n"
            Files.writeString(target, original)
            val policy = VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger())
            policy.initialSettings.viewerMbps shouldBe 2.0
            policy.initialSettings.serverMbps shouldBe 32.0
            policy.initialSettings.sourceViewerMbps shouldBe mapOf("game:video.wall" to 24.0)
            Files.readString(target) shouldBe original
            Files.writeString(target, "source-viewer-mbps: {game:video: nope}\n")
            shouldThrow<IllegalArgumentException> { policy.prepareReload() }
            Files.writeString(target, "source-viewer-mbps: {}\n")
            policy.prepareReload().sourceViewerMbps shouldBe emptyMap()
            Files.readString(target) shouldBe "source-viewer-mbps: {}\n"
        }
    }

    "invalid existing or imported policy fails startup without silently using defaults" {
        withPolicyRoot { root ->
            val host = root.resolve("ArcCore")
            Files.createDirectories(host)
            val target = host.resolve("visual-packets.yml")
            listOf("", "server-mbps: nope\n", "server-mbps: -1.0\n", "server-mbps: [\n").forEach { broken ->
                Files.writeString(target, broken)
                shouldThrow<IllegalArgumentException> { VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger()) }
                Files.readString(target) shouldBe broken
            }
            Files.delete(target)
            val legacy = root.resolve("ARC/modules/visual-packets.yml")
            Files.createDirectories(legacy.parent)
            Files.writeString(legacy, "viewer-mbps: -3.0\n")
            shouldThrow<IllegalArgumentException> { VisualPacketServiceConfig.load(host, Logger.getAnonymousLogger()) }
            Files.readString(target) shouldBe Files.readString(legacy)
        }
    }
})

private fun withPolicyRoot(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("arccore-visual-policy-")
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}
