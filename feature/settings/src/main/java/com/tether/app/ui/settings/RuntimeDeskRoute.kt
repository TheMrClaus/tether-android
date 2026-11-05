package com.tether.app.ui.settings

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** Stable local route keys for the Runtime Desk. They are never sent to the server. */
object RuntimeDeskDestinations {
    const val Preferences = "preferences"
    const val AccessDevices = "access-devices"
    const val HostsNodes = "hosts-nodes"
    const val Engines = "engines"
    const val Accounts = "accounts"
    const val SessionsRouting = "sessions-routing"
    const val ServerIntegrations = "server-integrations"

    val items = listOf(
        RuntimeDeskNavItem(Preferences, "Preferences", "Workspace, appearance, and notifications"),
        RuntimeDeskNavItem(AccessDevices, "Access & devices", "Passkeys, sessions, and paired devices"),
        RuntimeDeskNavItem(HostsNodes, "Hosts & nodes", "Node registry and credentials"),
        RuntimeDeskNavItem(Engines, "Engines", "Runtime detection and configuration"),
        RuntimeDeskNavItem(Accounts, "Accounts", "Credentials, sync, limits, and balances"),
        RuntimeDeskNavItem(SessionsRouting, "Sessions & routing", "Metadata, lifecycle, and defaults"),
        RuntimeDeskNavItem(ServerIntegrations, "Server & integrations", "Network, storage, secrets, and GitHub"),
    )

    fun hasEntities(destination: String): Boolean = destination == Engines || destination == Accounts
}

object RuntimeDeskEntities {
    const val Credentials = "credentials"
    const val AccountSync = "account-sync"
    const val LimitsBalance = "limits-balance"
    fun credential(accountId: String) = "credential:$accountId"
}

data class RuntimeDeskRoute(
    val destination: String = RuntimeDeskDestinations.Preferences,
    val entity: String? = null,
    val level: RuntimeDeskLevel = RuntimeDeskLevel.Destinations,
) {
    fun selectDestination(id: String): RuntimeDeskRoute {
        val entity = when (id) {
            RuntimeDeskDestinations.Engines -> this.entity?.takeIf { it in ENGINE_IDS } ?: ENGINE_IDS.first()
            RuntimeDeskDestinations.Accounts -> this.entity?.takeIf {
                it == RuntimeDeskEntities.Credentials ||
                    it == RuntimeDeskEntities.AccountSync ||
                    it == RuntimeDeskEntities.LimitsBalance ||
                    it.startsWith("credential:") ||
                    it.startsWith("usage:")
            } ?: RuntimeDeskEntities.Credentials
            else -> null
        }
        return RuntimeDeskRoute(
            destination = id,
            entity = entity,
            level = if (RuntimeDeskDestinations.hasEntities(id)) RuntimeDeskLevel.Entities else RuntimeDeskLevel.Detail,
        )
    }

    fun selectEntity(id: String): RuntimeDeskRoute = copy(entity = id, level = RuntimeDeskLevel.Detail)

    fun back(): RuntimeDeskRoute = when (level) {
        RuntimeDeskLevel.Detail -> if (RuntimeDeskDestinations.hasEntities(destination)) {
            copy(level = RuntimeDeskLevel.Entities)
        } else {
            copy(level = RuntimeDeskLevel.Destinations)
        }
        RuntimeDeskLevel.Entities -> copy(level = RuntimeDeskLevel.Destinations)
        RuntimeDeskLevel.Destinations -> this
    }

    companion object {
        val Root = RuntimeDeskRoute()
        val ENGINE_IDS = listOf("claude", "codex", "opencode", "reasonix", "pi", "dsh")
        val Saver: Saver<RuntimeDeskRoute, Any> = listSaver(
            save = { listOf(it.destination, it.entity.orEmpty(), it.level.name) },
            restore = {
                RuntimeDeskRoute(
                    destination = it[0] as String,
                    entity = (it[1] as String).ifEmpty { null },
                    level = runCatching { RuntimeDeskLevel.valueOf(it[2] as String) }.getOrDefault(RuntimeDeskLevel.Destinations),
                )
            },
        )

        fun fromLegacyTab(tab: SettingsTab): RuntimeDeskRoute = when (tab) {
            SettingsTab.General, SettingsTab.Appearance -> RuntimeDeskRoute(RuntimeDeskDestinations.Preferences, level = RuntimeDeskLevel.Detail)
            SettingsTab.Devices -> RuntimeDeskRoute(RuntimeDeskDestinations.AccessDevices, level = RuntimeDeskLevel.Detail)
            SettingsTab.Nodes -> RuntimeDeskRoute(RuntimeDeskDestinations.HostsNodes, level = RuntimeDeskLevel.Detail)
            SettingsTab.Engines -> RuntimeDeskRoute(RuntimeDeskDestinations.Engines, ENGINE_IDS.first(), RuntimeDeskLevel.Detail)
            SettingsTab.Metadata -> RuntimeDeskRoute(RuntimeDeskDestinations.SessionsRouting, level = RuntimeDeskLevel.Detail)
            SettingsTab.Advanced -> RuntimeDeskRoute(RuntimeDeskDestinations.ServerIntegrations, level = RuntimeDeskLevel.Detail)
        }
    }
}

internal fun RuntimeDeskRoute.legacyTab(): SettingsTab = when (destination) {
    RuntimeDeskDestinations.Preferences -> SettingsTab.General
    RuntimeDeskDestinations.AccessDevices -> SettingsTab.Devices
    RuntimeDeskDestinations.HostsNodes -> SettingsTab.Nodes
    RuntimeDeskDestinations.Engines, RuntimeDeskDestinations.Accounts -> SettingsTab.Engines
    RuntimeDeskDestinations.SessionsRouting -> SettingsTab.Metadata
    RuntimeDeskDestinations.ServerIntegrations -> SettingsTab.Advanced
    else -> SettingsTab.General
}
