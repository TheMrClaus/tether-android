package com.tether.app.ui.settings

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.tether.app.ui.icons.TetherIcons

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
        RuntimeDeskNavItem(Preferences, "Preferences", "Choose startup, session behavior, appearance, sign-in screen, and notifications", icon = TetherIcons.Settings, group = "Set up Tether"),
        RuntimeDeskNavItem(AccessDevices, "Access & devices", "Secure Tether sign-in; manage passkeys, signed-in sessions, and paired devices", icon = TetherIcons.Laptop, group = "Set up Tether"),
        RuntimeDeskNavItem(Engines, "Engines", "Set up engines, custom profiles, host config, and Claude CLI", icon = TetherIcons.SquareTerminal, group = "Run agents"),
        RuntimeDeskNavItem(Accounts, "Accounts", "Connect provider accounts; sync and review limits and balances", icon = TetherIcons.UserRound, group = "Run agents"),
        RuntimeDeskNavItem(SessionsRouting, "Sessions & routing", "Control metadata, lifecycle, session defaults, and contextual providers", icon = TetherIcons.GitBranch, group = "Run agents"),
        RuntimeDeskNavItem(HostsNodes, "Hosts & nodes", "Add, probe, and remove nodes; check status, versions, and credentials", icon = TetherIcons.Network, group = "Manage infrastructure"),
        RuntimeDeskNavItem(ServerIntegrations, "Server & integrations", "Configure network, authentication, storage, and GitHub", icon = TetherIcons.ServerCog, group = "Manage infrastructure"),
    )

    fun hasEntities(destination: String): Boolean = destination == Engines || destination == Accounts
}

object RuntimeDeskEntities {
    const val Credentials = "credentials"
    const val AccountSync = "account-sync"
    const val LimitsBalance = "limits-balance"
    const val CustomProfiles = "custom-profiles"
    const val HostConfig = "host-config"
    const val ClaudeCli = "claude-cli"
    fun account(provider: String, accountId: String) = "account:$provider:$accountId"
    fun usage(provider: String, accountId: String) = "usage:$provider:$accountId"
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
                    it.startsWith("account:") ||
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
        val BUILT_IN_ENGINE_IDS = listOf("claude", "codex", "opencode", "reasonix", "pi", "dsh")
        val ENGINE_TASK_IDS = listOf(RuntimeDeskEntities.CustomProfiles, RuntimeDeskEntities.HostConfig, RuntimeDeskEntities.ClaudeCli)
        val ENGINE_IDS = BUILT_IN_ENGINE_IDS + ENGINE_TASK_IDS
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
