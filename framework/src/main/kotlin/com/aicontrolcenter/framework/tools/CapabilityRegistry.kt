package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo

/**
 * The capabilities tools can require, with what the owner is told about each. It starts with
 * the framework's built-in ones; subsystems that bring tools needing a new capability add it
 * here at start-up. Safe for concurrent use.
 */
class CapabilityRegistry(initial: List<CapabilityInfo> = Capabilities.builtIn) {
    private val lock = Any()
    private val known = LinkedHashMap<Capability, CapabilityInfo>()

    init {
        initial.forEach(::register)
    }

    fun all(): List<CapabilityInfo> = synchronized(lock) { known.values.toList() }

    fun info(capability: Capability): CapabilityInfo? = synchronized(lock) { known[capability] }

    /** @throws IllegalArgumentException if the capability is already described. */
    fun register(info: CapabilityInfo) {
        synchronized(lock) {
            require(info.capability !in known) { "Capability ${info.capability} is already registered." }
            known[info.capability] = info
        }
    }
}
