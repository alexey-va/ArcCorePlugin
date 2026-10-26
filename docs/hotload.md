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
