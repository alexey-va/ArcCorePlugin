# ArcCore

Small Paper plugin that owns the shared SQL pools, Redis connections, metrics
and schematic service used by RusCrafting plugins. Runtime name: **ArcCore**. Extracted from
[`ARC/arc-core-plugin`](https://github.com/alexey-va/ARC/tree/6cca762ace28950e107506661665c644c66dfb6c/arc-core-plugin)
without changing the provider lifecycle.

The reusable implementations live in [`arc-core`](https://github.com/alexey-va/arc-core).
This repository builds the deployable host independently of ARC. It does not
start a second metrics HTTP exporter; ARC keeps the existing registry/exporter
and attaches it to the shared metrics service.

## Shared schematics

When WorldEdit is enabled, the host installs one
`PaperSchematicServiceProvider` from `arc-core-paper-schematics`. Its
`ArcSchematicService` contract is registered through Bukkit ServicesManager.
Without WorldEdit the schematic service is absent; SQL, Redis and metrics remain
available. Consumers resolve the service at use time and handle its absence.

Each consumer opens a library with an explicit root. For example, ArcBuilder
continues to use its configured `../ARC/schematics` catalogue. ArcCore does not
guess legacy paths, move files, walk a catalogue, or preload schematics during
startup. The shared implementation owns asynchronous retrieval, a bounded TTL
cache, worker admission and Paper placement tasks.

`plugins/ArcCore/schematics.yml` configures global cache memory/entry limits,
TTL, compressed and inflated file limits, volume limits, worker capacity and
Paper work per tick. Missing keys merge from the bundled defaults while existing
values and unknown keys remain. Invalid bounds fall back to the built-in
settings with a warning. Settings apply on the next server restart; ArcCore has
no reload command and consumers must not hot-replace its provider while work is
in flight. The host unregisters and closes the service when disabled.

The runtime JAR owns every `ArcSchematic*` API class and the schematic provider
implementation. Consumers compile against the shared API without shading either
family. WorldEdit stays an optional external plugin and is never bundled.

## Build and publish

Use JDK 25. GitHub Actions verifies the provider-host contract, runs lifecycle
tests, builds the production JAR and checks its class ownership. A successful
push to `main` publishes the exact tested artifact to the public Maven repository:

```text
ru.ruscrafting:ArcCore:0.1.2
https://repo.rus-crafting.ru/grocermc/
```

Change `pluginVersion` in `gradle.properties` for each artifact change. Published
coordinates are immutable; CI compares existing bytes and verifies anonymous
download checksums. Publication uses the repository secret
`REPOSILITE_PUBLISH_PASSWORD`. Pull requests test and package without publishing.

The workflow runs:

```sh
./gradlew --no-daemon test shadowJar publishRuntimePluginPublicationToStagingRepository
python3 scripts/verify-artifact.py
```

For coordinated local library changes, opt into composite substitution:

```sh
./gradlew -ParcCoreDir=/absolute/path/to/arc-core test shadowJar publishRuntimePluginPublicationToStagingRepository
python3 ../arc-core/scripts/verify_consumer_architecture.py .
python3 scripts/verify-artifact.py
```

Consumers use the pinned coordinate as `compileOnly`, and as `testImplementation`
when tests load the host. The runtime POM intentionally has no transitive
dependencies. Add `depend: [ArcCore]` to the consumer's plugin descriptor.

## Deploy independently

Download the JAR from the successful [Actions run](https://github.com/alexey-va/ArcCorePlugin/actions)
or the immutable Maven coordinate. Verify its SHA-256 against the run artifact.
From the existing `ruscrafting-ops` checkout:

```sh
./scripts/mc jar --no-restart --dry-run classic classic_survival parkour -- /absolute/path/ArcCore-0.1.2.jar
./scripts/mc jar --no-restart classic classic_survival parkour -- /absolute/path/ArcCore-0.1.2.jar
```

This updates only ArcCore. Activate through the normal notified/shared restart
workflow for the approved runtimes, then check plugin versions, shared service
diagnostics and startup logs. A runtime with deferred activation keeps its old
loaded version until its next authorized restart. First-time installation uses
the ops workflow's explicit `--install` option after dependency preflight.
