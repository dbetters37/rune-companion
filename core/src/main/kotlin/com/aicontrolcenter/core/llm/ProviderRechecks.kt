package com.aicontrolcenter.core.llm

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.registry.ConnectionStatus

/**
 * What agent-facing work may ask of the providers (Phase 6, K15, D28; split from
 * [ProviderManager] by the security review of wave 1, F3): look again at a provider that was not
 * answering before waiting for it, and learn when the next scheduled look is due. It can add,
 * change, remove or reveal nothing. The orchestrator and chat (WP7) and the wake lock (WP2) hold
 * this, never [ProviderManager]; WP4 builds it (`DefaultProviderRechecks`), and it must not hold a
 * [ProviderManager] or the vault itself (the architecture tests walk into it).
 */
interface ProviderRechecks {
    /**
     * Checks provider [providerId] again if its stored status is older than [olderThanMs] (or it
     * was never checked), within a bounded time, and returns the status it has now; null when the
     * provider is unknown or nothing was checked. Never checks a provider off the phone in
     * local-only mode, and never a provider whose key was rejected. Work about to wait for a
     * provider that is not answering asks this first.
     */
    suspend fun recheckIfStale(providerId: ProviderId, olderThanMs: Long): ConnectionStatus?

    /** When the next scheduled re-check of a provider that is not answering is due (rule 5). */
    val timedWaits: TimedWaits

    /** Checks nothing; nothing is due. */
    object None : ProviderRechecks {
        override suspend fun recheckIfStale(providerId: ProviderId, olderThanMs: Long): ConnectionStatus? = null

        override val timedWaits: TimedWaits get() = TimedWaits.None
    }
}

/**
 * The owner's side of rule 32 (Phase 6; split from [ProviderManager] by the security review of
 * wave 1, F3): whether a text the owner is about to send an agent holds one of the provider keys
 * stored in the vault. Owner-only: it answers "is this text a stored key?", an oracle no agent may
 * reach. Only the owner's answer path (`OwnerServices.answer`, WP5) holds it; both architecture
 * tests list it. WP4 builds it over the vault.
 */
interface OwnerKeyCheck {
    /**
     * True when [text] contains, or is, one of the provider keys stored in the vault, compared in
     * constant time. Never returns or logs a key.
     */
    suspend fun matchesStoredKey(text: String): Boolean

    /** No stored key to compare with: always false (until WP4 wires the vault's). */
    object None : OwnerKeyCheck {
        override suspend fun matchesStoredKey(text: String): Boolean = false
    }
}
