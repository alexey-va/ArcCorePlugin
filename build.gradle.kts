import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    `maven-publish`
}

group = "ru.ruscrafting"
version = providers.gradleProperty("pluginVersion").get()
base.archivesName.set("ArcCore")

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

repositories {
    maven("https://repo.rus-crafting.ru/grocermc/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/") {
        content {
            includeGroupByRegex("com\\.sk89q(\\..*)?")
            includeGroupByRegex("org\\.enginehub(\\..*)?")
        }
    }
    mavenCentral()
}

// Only this immutable DTO crosses the host/ARC metrics boundary. Do not host
// another collector, Prometheus registry or exporter by importing the whole module.
val visualMetricsContract by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.79")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.79")
    implementation("ru.ruscrafting.arc:arc-core-paper-schematics:2.7.79")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.79")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.79")
    visualMetricsContract("ru.ruscrafting.arc:arc-core-metrics:2.7.79")

    // PaperArcMetricsProvider is hosted here; ARC keeps its own registry and exporter.
    implementation("io.micrometer:micrometer-core:1.14.5")
    implementation("io.micrometer:micrometer-commons:1.14.5")
    implementation("io.micrometer:micrometer-observation:1.14.5")

    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.79")
    testImplementation("ru.ruscrafting.arc:arc-core-metrics:2.7.79") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testRuntimeOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar { archiveClassifier.set("plain") }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    // Shared resource, public scene and visual admission contracts have one host class identity.
    exclude {
        it.path.startsWith("ru/arc/paper/api/") && !it.isDirectory &&
            listOf("ArcSql", "ArcRedis", "ArcMetric", "ArcSchematic", "ArcBlockProtection", "ArcVisualPacketBudget", "ArcDisplayPassenger", "ArcNativeCleanup", "ArcAfkProvider", "ArcNameplateClaim", "ArcScene", "VisualPacketAdmission")
                .none { prefix -> it.name.startsWith(prefix) }
    }
    // arc-core-paper also contains ARC's optional Paper collector; the host owns
    // the shared budget and DTO only, while ARC keeps its collector/exporter.
    exclude("ru/arc/metrics/paper/**")
    from({ visualMetricsContract.map { zipTree(it) } }) { include("ru/arc/metrics/core/MetricPoint*.class") }
    exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
}

tasks.build { dependsOn(tasks.shadowJar) }

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }
    }
    publications {
        create<MavenPublication>("runtimePlugin") {
            groupId = "ru.ruscrafting"
            artifactId = "ArcCore"
            version = project.version.toString()
            artifact(tasks.shadowJar)
            pom {
                name.set("ArcCore")
                description.set("Shared SQL, Redis, metrics, schematic and visual packet provider host for RusCrafting Paper plugins")
                url.set("https://github.com/alexey-va/ArcCorePlugin")
            }
        }
    }
}
