package ru.arc.coreplugin

import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.ChestDialogViewProvider
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.DialogViewProvider
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.storage.ChestDialogStorage
import com.viaversion.viaversion.api.Via
import com.viaversion.viaversion.api.ViaManager
import com.viaversion.viaversion.api.connection.UserConnection
import com.viaversion.viaversion.api.platform.providers.ViaProviders
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/** Optional ViaBackwards 5.12.1 provider integration, loaded only when both Via plugins are enabled. */
internal class ViaBackwardsDialogCompatibility private constructor(
    private val providers: ViaProviders,
    private val original: DialogViewProvider,
    private val replacement: ChestDialogViewProvider,
) : AutoCloseable {
    override fun close() {
        // A later addon may legitimately own this provider; never undo its registration.
        if (providers.get(DialogViewProvider::class.java) === replacement) {
            providers.use(DialogViewProvider::class.java, original)
        }
    }

    companion object {
        fun install(plugin: Plugin): ViaBackwardsDialogCompatibility? = install(Via.getManager(), plugin.logger)

        internal fun install(manager: ViaManager, logger: Logger): ViaBackwardsDialogCompatibility? {
            if (!manager.isInitialized) {
                logger.warning("Legacy dialog compatibility unavailable: ViaVersion has not initialized")
                return null
            }
            val providers = manager.providers
            val original = providers.get(DialogViewProvider::class.java)
            if (original?.javaClass != ChestDialogViewProvider::class.java) {
                logger.warning("Legacy dialog compatibility skipped: expected the default ViaBackwards provider, found ${original?.javaClass?.name ?: "none"}")
                return null
            }
            val protocol = manager.protocolManager.getProtocol(Protocol1_21_6To1_21_5::class.java)
            if (protocol == null) {
                logger.warning("Legacy dialog compatibility unavailable: ViaBackwards 1.21.6 translation is not registered")
                return null
            }
            val replacement = ClosingChestDialogViewProvider(protocol)
            providers.use(DialogViewProvider::class.java, replacement)
            logger.info("Installed ViaBackwards 5.12.1 dialog close compatibility")
            return ViaBackwardsDialogCompatibility(providers, original, replacement)
        }
    }
}

internal class ClosingChestDialogViewProvider(protocol: Protocol1_21_6To1_21_5) : ChestDialogViewProvider(protocol) {
    override fun closeDialog(connection: UserConnection) {
        // 5.12.1 reopens the previous dialog while retaining the current storage,
        // turning successive server screens into an endless A/B close loop.
        // Core owns Back navigation; CLOSE and CLEAR must actually dismiss the view.
        connection.get(ChestDialogStorage::class.java)?.setPreviousDialog(null)
        super.closeDialog(connection)
    }
}
