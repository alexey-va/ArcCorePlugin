package ru.arc.coreplugin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import ru.arc.paper.api.ArcSchematicCacheSettings
import java.nio.file.Files
import java.util.logging.Logger

class SchematicServiceConfigTest : StringSpec({
    "default and bundled resource limits match the shared API defaults" {
        TestSchematicServiceConfig().snapshot() shouldBe ArcSchematicCacheSettings.defaults()
        val root = Files.createTempDirectory("arccore-schematics-config-")
        try {
            SchematicServiceConfig.load(root).snapshot() shouldBe ArcSchematicCacheSettings.defaults()
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "merges new resource settings without overwriting operator values" {
        val root = Files.createTempDirectory("arccore-schematics-config-")
        try {
            val file = root.resolve("schematics.yml")
            Files.writeString(file, "cache:\n  ttl: 5m\n  maximum-entries: 12\noperator-note: keep\n")
            val settings = SchematicServiceConfig.load(root).snapshot()
            settings.ttlMillis shouldBe 300_000L
            settings.maximumEntries shouldBe 12
            settings.workerThreads shouldBe ArcSchematicCacheSettings.defaults().workerThreads
            val first = Files.readString(file)
            SchematicServiceConfig.load(root).snapshot() shouldBe settings
            Files.readString(file) shouldBe first
            Config(root, "schematics.yml").string("operator-note") shouldBe "keep"
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "invalid resource bounds are rejected before constructing the provider" {
        shouldThrow<IllegalArgumentException> {
            object : TestSchematicServiceConfig() {
                override val maximumEntries = 0
            }.snapshot()
        }
    }

    "invalid operator bounds fall back without preventing the host from starting" {
        val root = Files.createTempDirectory("arccore-schematics-config-")
        try {
            Files.writeString(root.resolve("schematics.yml"), "cache:\n  maximum-entries: 0\n")
            SchematicServiceConfig.loadSettings(root, Logger.getAnonymousLogger()) shouldBe ArcSchematicCacheSettings.defaults()
            Config(root, "schematics.yml").int("cache.maximum-entries") shouldBe 0
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})

private open class TestSchematicServiceConfig : SchematicServiceConfig(EmptyConfig)
