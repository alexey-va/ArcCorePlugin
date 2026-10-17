#!/usr/bin/env python3
"""Verify the standalone runtime JAR and its dependency-free publication."""
from pathlib import Path
from collections import Counter
import xml.etree.ElementTree as ET
import zipfile


SHARED_VISUAL_CLASSES = (
    "ru/arc/paper/api/ArcVisualPacketBudget.class",
    "ru/arc/paper/api/VisualPacketAdmission.class",
    "ru/arc/paper/packet/PaperVisualPacketRuntime.class",
    "ru/arc/paper/packet/PaperVisualPacketRuntime$Settings.class",
    "ru/arc/paper/packet/PaperVisualPacketRuntime$RuntimeSnapshot.class",
    "ru/arc/paper/packet/PaperVisualPacketRuntime$CounterSnapshot.class",
    "ru/arc/metrics/core/MetricPoint.class",
)


def verify_visual_ownership(names: list[str]) -> None:
    counts = Counter(names)
    for name in SHARED_VISUAL_CLASSES:
        assert counts[name] == 1, f"Shared visual class must have exactly one host definition: {name}"
    assert "visual-packets.yml" in counts, "Missing canonical visual packet policy"
    for name in counts:
        if name.endswith(".class") and name.startswith("ru/arc/metrics/"):
            assert name.startswith("ru/arc/metrics/core/MetricPoint"), \
                f"Host must embed only the shared MetricPoint DTO, not another metrics runtime: {name}"
        assert not name.startswith(("io/micrometer/prometheus/", "io/micrometer/prometheusmetrics/")), \
            f"Host must not embed another Prometheus registry/exporter: {name}"


def verify(*, publication: bool = True) -> None:
    version = next(
        line.partition("=")[2].strip()
        for line in Path("gradle.properties").read_text().splitlines()
        if line.startswith("pluginVersion=")
    )
    jar = Path(f"build/libs/ArcCore-{version}.jar")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        entries = set(names)
        descriptor = archive.read("plugin.yml").decode()
    verify_visual_ownership(names)
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
            assert name.rsplit("/", 1)[-1].startswith(("ArcSql", "ArcRedis", "ArcMetric", "ArcSchematic", "ArcBlockProtection", "ArcVisualPacketBudget", "VisualPacketAdmission")), \
                f"Non-host API embedded: {name}"
    if not publication:
        print(f"Verified standalone host JAR: {jar}")
        return
    publication = Path(f"build/release-repository/ru/ruscrafting/ArcCore/{version}")
    assert jar.read_bytes() == (publication / jar.name).read_bytes(), "Staged JAR differs from tested JAR"
    pom = ET.parse(publication / f"ArcCore-{version}.pom").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    coordinate = tuple(pom.findtext(f"m:{key}", namespaces=ns) for key in ("groupId", "artifactId", "version"))
    assert coordinate == ("ru.ruscrafting", "ArcCore", version), f"Wrong coordinate: {coordinate}"
    assert not pom.findall("m:dependencies/m:dependency", ns), "Runtime POM must not propagate dependencies"
    print(f"Verified standalone host and publication: {jar}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar-only", action="store_true", help="Verify local JAR without CI-owned Maven staging")
    verify(publication=not parser.parse_args().jar_only)
