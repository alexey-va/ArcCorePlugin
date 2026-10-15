# ArcCorePlugin

- Runtime plugin name: `ArcCore`. This repository owns the small Paper host;
  shared library implementations remain in `alexey-va/arc-core`.
- Keep the host independent of ARC and feature plugins. SQL, Redis and metrics
  providers are registered through Bukkit ServicesManager and closed on disable.
- Keep all core library coordinates aligned with `arc-core-consumer.toml`.
  This is a provider host, not a feature consumer: do not add unused feature
  scheduling, locale, logging or gameplay infrastructure.
- Keep resource service API classes and providers in the runtime JAR; visual
  and domain APIs remain in their existing owners. Preserve the packaging gate.
- Builds and JVM tests run in GitHub Actions with JDK 25. Successful `main`
  pushes publish immutable Maven artifacts. Bump `pluginVersion` when published
  bytes change; never overwrite a released coordinate.
- Consumers use the pinned `ru.ruscrafting:ArcCore` coordinate as `compileOnly`
  and declare `depend: [ArcCore]`. Never shade this plugin into another JAR.
- Deploy only the tested ArcCore JAR with the existing ops `mc jar` workflow.
  Respect per-runtime deferred activation and restart authorization.
