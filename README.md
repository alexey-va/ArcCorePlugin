# ArcCore

Small Paper plugin that owns the shared SQL pools, Redis connections and metrics
service used by RusCrafting plugins. Runtime name: **ArcCore**. Extracted from
[`ARC/arc-core-plugin`](https://github.com/alexey-va/ARC/tree/6cca762ace28950e107506661665c644c66dfb6c/arc-core-plugin)
without changing the provider lifecycle.

The reusable implementations live in [`arc-core`](https://github.com/alexey-va/arc-core).
This repository builds the deployable host independently of ARC. It does not
start a second metrics HTTP exporter; ARC keeps the existing registry/exporter
and attaches it to the shared metrics service.

## Build and publish

Use JDK 25. GitHub Actions verifies the provider-host contract, runs lifecycle
tests, builds the production JAR and checks its class ownership. A successful
push to `main` publishes the exact tested artifact to the public Maven repository:

```text
ru.ruscrafting:ArcCore:0.1.1
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

Consumers use the pinned coordinate as `compileOnly`, and as `testImplementation`
when tests load the host. The runtime POM intentionally has no transitive
dependencies. Add `depend: [ArcCore]` to the consumer's plugin descriptor.

## Deploy independently

Download the JAR from the successful [Actions run](https://github.com/alexey-va/ArcCorePlugin/actions)
or the immutable Maven coordinate. Verify its SHA-256 against the run artifact.
From the existing `ruscrafting-ops` checkout:

```sh
./scripts/mc jar --no-restart --dry-run classic classic_survival parkour -- /absolute/path/ArcCore-0.1.1.jar
./scripts/mc jar --no-restart classic classic_survival parkour -- /absolute/path/ArcCore-0.1.1.jar
```

This updates only ArcCore. Activate through the normal notified/shared restart
workflow for the approved runtimes, then check plugin versions, shared service
diagnostics and startup logs. A runtime with deferred activation keeps its old
loaded version until its next authorized restart. First-time installation uses
the ops workflow's explicit `--install` option after dependency preflight.
