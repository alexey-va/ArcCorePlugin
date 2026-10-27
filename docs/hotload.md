# ArcCore provider lifecycle

ArcCore is a shared provider and is not eligible for independent code hotload.
Keep it enabled while gameplay consumers prepare and drain. Its SQL/Redis
leases, native cleanup executor, visual packet provider and exported API classes
belong to the current provider generation; unregistering a service does not
invalidate references already held by consumers.

Replace ArcCore and any changed public API together through an authorized
ordinary server restart. Stop dependent consumers before the provider, and let
their storage and native retirement stages finish while the provider still
runs. Never unload ArcCore with active consumers, and never use reload-all as a
replacement for this ordering. There is no ArcCore hotload-ready receipt.

`onDisable` closes all registered providers and waits for their accepted
storage/transport work. It is final server-shutdown cleanup, not a veto hook
for a loader and not proof that loading a second ArcCore classloader is safe.
Startup uses the same resource owners and removes partial service registrations
if installation fails.

ArcCore 0.1.22 hosts the additive `ArcSceneObservationService` contract from Core
2.7.71. Public-scene source leases are synchronous and Main-only. Consumer
disable removes its sources; host close clears all callbacks and unregisters
the service. Replacing the host/API still requires the coordinated server restart
above, even though retained consumers use byte-identical pre-existing API classes.
