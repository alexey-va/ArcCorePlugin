#!/usr/bin/env python3
"""Verify the standalone runtime JAR and its dependency-free publication."""
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


def verify() -> None:
    version = next(
        line.partition("=")[2].strip()
        for line in Path("gradle.properties").read_text().splitlines()
        if line.startswith("pluginVersion=")
    )
    jar = Path(f"build/libs/ArcCore-{version}.jar")
    with zipfile.ZipFile(jar) as archive:
        entries = set(archive.namelist())
        descriptor = archive.read("plugin.yml").decode()
    fields = {key: value.strip().strip("'\"") for key, _, value in
              (line.partition(":") for line in descriptor.splitlines())}
    for key, value in {"name": "ArcCore", "version": version, "main": "ru.arc.coreplugin.ArcCorePlugin"}.items():
        assert fields.get(key) == value, f"Unexpected descriptor entry: {key}"
    assert "depend" not in fields and "loadbefore" not in fields, \
        "ArcCore must start independently of feature plugins"
    assert fields.get("softdepend") == "[WorldEdit]", \
        "ArcCore may optionally order after WorldEdit only"
    required = (
        "ru/arc/coreplugin/ArcCorePlugin.class",
        "ru/arc/coreplugin/ArcCoreServices.class",
        "ru/arc/paper/api/ArcSqlService.class",
        "ru/arc/paper/api/ArcRedisService.class",
        "ru/arc/paper/api/ArcMetricsService.class",
        "ru/arc/paper/api/ArcSchematicService.class",
        "ru/arc/paper/api/ArcBlockProtectionService.class",
        "ru/arc/paper/api/ArcSchematicCacheSettings.class",
        "ru/arc/paper/sql/PaperSqlServiceProvider.class",
        "ru/arc/paper/sql/SharedPaperSqlService.class",
        "ru/arc/paper/redis/PaperRedisServiceProvider.class",
        "ru/arc/paper/redis/SharedPaperRedisService.class",
        "ru/arc/paper/metrics/PaperArcMetricsProvider.class",
        "ru/arc/paper/schematics/PaperSchematicServiceProvider.class",
        "io/micrometer/core/instrument/MeterRegistry.class",
        "kotlin/collections/CollectionsKt.class",
    )
    for name in required:
        assert name in entries, f"Missing host class: {name}"
    assert "schematics.yml" in entries, "Missing schematic resource limits"
    for name in entries:
        if not name.endswith(".class"):
            continue
        assert not name.startswith(("org/bukkit/", "io/papermc/", "com/sk89q/", "ru/arc/dungeons/", "ru/arc/mounts/")), \
            f"Server, WorldEdit or domain class embedded: {name}"
        if name.startswith("ru/arc/paper/api/"):
            assert name.rsplit("/", 1)[-1].startswith(("ArcSql", "ArcRedis", "ArcMetric", "ArcSchematic", "ArcBlockProtection")), \
                f"Non-resource API embedded: {name}"
    publication = Path(f"build/release-repository/ru/ruscrafting/ArcCore/{version}")
    assert jar.read_bytes() == (publication / jar.name).read_bytes(), "Staged JAR differs from tested JAR"
    pom = ET.parse(publication / f"ArcCore-{version}.pom").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    coordinate = tuple(pom.findtext(f"m:{key}", namespaces=ns) for key in ("groupId", "artifactId", "version"))
    assert coordinate == ("ru.ruscrafting", "ArcCore", version), f"Wrong coordinate: {coordinate}"
    assert not pom.findall("m:dependencies/m:dependency", ns), "Runtime POM must not propagate dependencies"
    print(f"Verified standalone host and publication: {jar}")


if __name__ == "__main__":
    verify()
