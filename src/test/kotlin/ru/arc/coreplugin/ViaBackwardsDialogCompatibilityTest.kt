package ru.arc.coreplugin

import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.Dialog
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.ChestDialogViewProvider
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.DialogViewProvider
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.storage.ChestDialogStorage
import com.viaversion.viaversion.api.ViaManager
import com.viaversion.viaversion.api.connection.UserConnection
import com.viaversion.viaversion.api.platform.providers.ViaProviders
import com.viaversion.viaversion.api.protocol.ProtocolManager
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper
import com.viaversion.viaversion.api.protocol.packet.State
import com.viaversion.viaversion.api.type.Types
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.packet.ClientboundPackets1_21_5
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.logging.Logger
import java.net.URLClassLoader

class ViaBackwardsDialogCompatibilityTest : StringSpec({
    "host class and lifecycle methods load without either Via dependency" {
        val hostClass = ArcCorePlugin::class.java
        val loader = object : URLClassLoader(arrayOf(hostClass.protectionDomain.codeSource.location), hostClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("com.viaversion.")) throw ClassNotFoundException(name)
                if (name.startsWith("ru.arc.coreplugin.")) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
                return super.loadClass(name, resolve)
            }
        }
        loader.use {
            val isolatedHost = Class.forName(hostClass.name, false, it)
            isolatedHost.classLoader shouldBe it
            isolatedHost.declaredMethods.map { method -> method.name }.shouldContainAll("onEnable", "onDisable")
            isolatedHost.declaredConstructors.size shouldBe 1
        }
    }

    "CLEAR and Escape close replaced dialogs without reopening stale screens" {
        val fixture = DialogConnection()
        val provider = spyk(ClosingChestDialogViewProvider(mockk()))
        every { provider.openChestView(any(), any(), any()) } just Runs
        val loading = mockk<Dialog> { every { widgets() } returns mutableListOf() }
        val target = mockk<Dialog> { every { widgets() } returns mutableListOf() }
        val closePacket = mockk<PacketWrapper>(relaxed = true)
        mockkStatic(PacketWrapper::class)
        try {
            every { PacketWrapper.create(ClientboundPackets1_21_5.CONTAINER_CLOSE, fixture.connection) } returns closePacket
            repeat(3) {
                provider.openDialog(fixture.connection, loading)
                provider.openDialog(fixture.connection, target)
                fixture.storage?.dialog() shouldBe target
                fixture.storage?.previousDialog() shouldBe loading
                // The actual upstream close implementation must remove this storage.
                if (it == 1) provider.clickButton(fixture.connection, Dialog.AfterAction.CLOSE, null)
                else provider.closeDialog(fixture.connection)
                fixture.storage shouldBe null
            }
            // Two shows per visit, with no synthetic reopening and one close per exit.
            verify(exactly = 6) { provider.openChestView(fixture.connection, any(), any()) }
            verify(exactly = 3) { closePacket.write(Types.VAR_INT, any()) }
            verify(exactly = 3) { closePacket.send(Protocol1_21_6To1_21_5::class.java) }
            provider.closeDialog(fixture.connection)
            verify(exactly = 3) { closePacket.send(Protocol1_21_6To1_21_5::class.java) }
        } finally {
            unmockkStatic(PacketWrapper::class)
        }
    }

    "installation replaces only the default provider and restores it on close" {
        val fixture = ProviderRegistry()
        val original = ChestDialogViewProvider(fixture.protocol)
        fixture.providers.use(DialogViewProvider::class.java, original)
        val lease = checkNotNull(ViaBackwardsDialogCompatibility.install(fixture.manager, fixture.logger))
        fixture.providers.get(DialogViewProvider::class.java).shouldBeInstanceOf<ClosingChestDialogViewProvider>()
        lease.close()
        lease.close()
        fixture.providers.get(DialogViewProvider::class.java) shouldBe original
    }

    "installation preserves an absent or foreign provider" {
        val fixture = ProviderRegistry()
        ViaBackwardsDialogCompatibility.install(fixture.manager, fixture.logger) shouldBe null
        fixture.providers.get(DialogViewProvider::class.java) shouldBe null
        val foreign = object : ChestDialogViewProvider(fixture.protocol) {}
        fixture.providers.use(DialogViewProvider::class.java, foreign)
        ViaBackwardsDialogCompatibility.install(fixture.manager, fixture.logger) shouldBe null
        fixture.providers.get(DialogViewProvider::class.java) shouldBe foreign
    }

    "shutdown cannot overwrite a later provider registration" {
        val fixture = ProviderRegistry()
        fixture.providers.use(DialogViewProvider::class.java, ChestDialogViewProvider(fixture.protocol))
        val lease = checkNotNull(ViaBackwardsDialogCompatibility.install(fixture.manager, fixture.logger))
        val later = mockk<DialogViewProvider>()
        fixture.providers.use(DialogViewProvider::class.java, later)
        lease.close()
        fixture.providers.get(DialogViewProvider::class.java) shouldBe later
    }

    "missing initialization or protocol cannot replace the provider" {
        val fixture = ProviderRegistry()
        val original = ChestDialogViewProvider(fixture.protocol)
        fixture.providers.use(DialogViewProvider::class.java, original)
        every { fixture.manager.isInitialized } returns false
        ViaBackwardsDialogCompatibility.install(fixture.manager, fixture.logger) shouldBe null
        fixture.providers.get(DialogViewProvider::class.java) shouldBe original
        every { fixture.manager.isInitialized } returns true
        every { fixture.protocols.getProtocol(Protocol1_21_6To1_21_5::class.java) } returns null
        ViaBackwardsDialogCompatibility.install(fixture.manager, fixture.logger) shouldBe null
        fixture.providers.get(DialogViewProvider::class.java) shouldBe original
    }
})

private class DialogConnection {
    var storage: ChestDialogStorage? = null
    val connection = mockk<UserConnection>()

    init {
        every { connection.protocolInfo } returns mockk { every { clientState } returns State.PLAY }
        every { connection.get(ChestDialogStorage::class.java) } answers { storage }
        every { connection.put(any<ChestDialogStorage>()) } answers { storage = firstArg() }
        every { connection.remove(ChestDialogStorage::class.java) } answers { storage.also { storage = null } }
    }
}

private class ProviderRegistry {
    val providers = ViaProviders()
    val protocol = mockk<Protocol1_21_6To1_21_5>()
    val protocols = mockk<ProtocolManager> {
        every { getProtocol(Protocol1_21_6To1_21_5::class.java) } returns protocol
    }
    val manager = mockk<ViaManager> {
        every { isInitialized } returns true
        every { getProviders() } returns this@ProviderRegistry.providers
        every { protocolManager } returns protocols
    }
    val logger: Logger = Logger.getAnonymousLogger()
}
