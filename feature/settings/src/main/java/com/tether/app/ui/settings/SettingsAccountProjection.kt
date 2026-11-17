package com.tether.app.ui.settings

import com.tether.app.client.ClaudeAccount
import com.tether.app.client.LabelText
import com.tether.app.client.Profile
import com.tether.app.client.ProvidersList

/** The unchanged usage API projected into the provider-qualified Settings identity. */
internal data class SettingsUsageAccount(
    val provider: String,
    val id: String,
    val label: String,
)

internal data class SettingsAccountProjection(
    val credentialAccounts: List<ClaudeAccount>,
    val accountBackedProfiles: List<Profile>,
    val customProfiles: List<Profile>,
    val hostDefaultAccountId: String,
    val usageAccounts: List<SettingsUsageAccount>,
    val usageIdByAccountId: Map<String, String>,
    val entities: List<RuntimeDeskNavItem>,
    val visibleClaudeUsageIds: Set<String>,
) {
    fun canonicalEntity(entity: String?): String? {
        val parts = accountEntityParts(entity) ?: return entity
        val accountId = if (parts.provider == "claude") {
            when {
                parts.accountId == "default" -> hostDefaultAccountId
                usageIdByAccountId.containsKey(parts.accountId) -> parts.accountId
                else -> usageIdByAccountId.entries.firstOrNull { it.value == parts.accountId }?.key ?: parts.accountId
            }
        } else {
            parts.accountId
        }
        return RuntimeDeskEntities.account(parts.provider, accountId)
    }

    fun credential(accountId: String): ClaudeAccount? = credentialAccounts.firstOrNull { it.id == accountId }

    fun accountProfile(accountId: String): Profile? = accountBackedProfiles.firstOrNull { it.id == accountId }

    fun usageEntity(accountEntity: String?): String? {
        val canonical = canonicalEntity(accountEntity)
        val parts = accountEntityParts(canonical) ?: return null
        val usageId = if (parts.provider == "claude") usageIdByAccountId[parts.accountId] ?: parts.accountId else parts.accountId
        return RuntimeDeskEntities.usage(parts.provider, usageId)
    }
}

internal data class AccountEntityParts(val provider: String, val accountId: String)

internal fun accountEntityParts(entity: String?): AccountEntityParts? {
    if (entity == null) return null
    val qualified = Regex("^(?:account|usage):(claude|codex|deepseek):(.+)$").matchEntire(entity)
    if (qualified != null) return AccountEntityParts(qualified.groupValues[1], qualified.groupValues[2])
    val credential = Regex("^credential:(.+)$").matchEntire(entity)
    return credential?.let { AccountEntityParts("claude", it.groupValues[1]) }
}

/**
 * Mirrors the final web Settings projection without changing either backend contract. Credential
 * rows, provider-registry rows and usage readings remain separate sources; only their navigation
 * identity is combined here.
 */
internal object SettingsAccountProjector {
    const val SyntheticHostDefault = "claude-default"

    fun isProviderAccount(account: ClaudeAccount): Boolean = account.managed || account.imported || account.hasConfigDir

    fun isAccountBacked(profile: Profile): Boolean =
        profile.extends == "claude" && profile.envValue("CLAUDE_CONFIG_DIR")?.isEmpty == false

    fun project(
        credentials: List<ClaudeAccount>,
        usageItems: List<RuntimeDeskNavItem>,
        providers: ProvidersList?,
    ): SettingsAccountProjection {
        val credentialAccounts = credentials.filter(::isProviderAccount).distinctBy { it.id }
        val credentialIds = credentialAccounts.mapTo(linkedSetOf()) { it.id }
        val profiles = providers?.profiles.orEmpty()
        val accountBackedProfiles = profiles.filter(::isAccountBacked)
        val customProfiles = profiles.filterNot(::isAccountBacked)

        val ordinaryProfileIds = linkedSetOf<String>()
        credentials.filterNot(::isProviderAccount).forEach { ordinaryProfileIds += it.id }
        profiles.filter { profile ->
            profile.extends == "claude" && profile.id !in credentialIds && !isAccountBacked(profile)
        }.forEach { ordinaryProfileIds += it.id }

        val persistedHostDefault = credentialAccounts.firstOrNull {
            it.id != SyntheticHostDefault && it.imported && it.syncEligible && !it.managed
        }
        val hostDefault = persistedHostDefault
            ?: credentialAccounts.firstOrNull { it.id == SyntheticHostDefault }
        val hostDefaultId = hostDefault?.id ?: SyntheticHostDefault

        val parsedUsage = usageItems.mapNotNull { item ->
            val parts = accountEntityParts(item.id) ?: return@mapNotNull null
            SettingsUsageAccount(parts.provider, parts.accountId, item.label)
        }
        val rawClaude = parsedUsage.filter { it.provider == "claude" }
        val hasDefaultUsage = rawClaude.any { it.id == "default" }
        val usageAccounts = buildList {
            val seen = linkedSetOf<Pair<String, String>>()
            parsedUsage.forEach { usage ->
                if (usage.provider == "claude") {
                    if (usage.id != "default" && usage.id in ordinaryProfileIds) return@forEach
                    if (hasDefaultUsage && persistedHostDefault != null && usage.id == persistedHostDefault.id) return@forEach
                }
                if (seen.add(usage.provider to usage.id)) {
                    add(
                        if (usage.provider == "claude" && usage.id == "default") {
                            usage.copy(label = "Claude Code (default)")
                        } else {
                            usage
                        },
                    )
                }
            }
        }

        val usageByAccount = linkedMapOf<String, String>()
        usageAccounts.filter { it.provider == "claude" }.forEach { usage ->
            usageByAccount.putIfAbsent(if (usage.id == "default") hostDefaultId else usage.id, usage.id)
        }

        val records = linkedMapOf<String, RuntimeDeskNavItem>()
        credentialAccounts.forEach { account ->
            val id = RuntimeDeskEntities.account("claude", account.id)
            records[id] = RuntimeDeskNavItem(
                id = id,
                label = safeLabel(account.label, account.id),
                supporting = if (account.imported) "Imported from this host" else account.plan?.label ?: "Claude account",
                group = "Provider accounts",
            )
        }
        accountBackedProfiles.forEach { profile ->
            val id = RuntimeDeskEntities.account("claude", profile.id)
            records.putIfAbsent(
                id,
                RuntimeDeskNavItem(id, safeLabel(profile.label, profile.id), "Credentials unavailable", group = "Provider accounts"),
            )
        }
        usageAccounts.forEachIndexed { index, usage ->
            val accountId = if (usage.provider == "claude" && usage.id == "default") hostDefaultId else usage.id
            val id = RuntimeDeskEntities.account(usage.provider, accountId)
            val label = when (usage.provider) {
                "claude" -> safeLabel(usage.label, if (usage.id == "default") "Claude Code (default)" else "Claude account")
                "codex" -> safeLabel(usage.label, "Codex")
                else -> "DeepSeek ${usageAccounts.take(index + 1).count { it.provider == "deepseek" }}"
            }
            records.putIfAbsent(id, RuntimeDeskNavItem(id, label, "Usage only", group = "Provider accounts"))
        }

        val labelCounts = records.values.groupingBy { it.label }.eachCount()
        val qualified = records.values.map { item ->
            if (labelCounts[item.label] == 1) item else {
                val parts = accountEntityParts(item.id)
                val provider = when (parts?.provider) {
                    "claude" -> "Claude"
                    "codex" -> "Codex"
                    else -> "DeepSeek"
                }
                item.copy(supporting = "$provider · ${parts?.accountId ?: item.id} · ${item.supporting ?: "Provider account"}")
            }
        }

        return SettingsAccountProjection(
            credentialAccounts = credentialAccounts,
            accountBackedProfiles = accountBackedProfiles,
            customProfiles = customProfiles,
            hostDefaultAccountId = hostDefaultId,
            usageAccounts = usageAccounts,
            usageIdByAccountId = usageByAccount,
            entities = qualified,
            visibleClaudeUsageIds = usageAccounts.filter { it.provider == "claude" }.mapTo(linkedSetOf()) { it.id },
        )
    }

    private fun safeLabel(label: String, fallback: String): String =
        LabelText.label(label).ifEmpty { LabelText.visibleValue(fallback) }
}
