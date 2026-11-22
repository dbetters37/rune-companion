package com.aicontrolcenter.framework

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.framework.agents.AgentDeletionListener
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.testkit.FakeAiAppScanner
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryAiAppRepository
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator

/** A complete in-memory framework for tests. */
class TestWorld {
    val clock = FakeClock(autoAdvanceMs = 1)
    val ids = SequentialIdGenerator()
    val agents = InMemoryAgentRepository()
    val providers = InMemoryProviderRepository()
    val models = InMemoryModelRepository()
    val apps = InMemoryAiAppRepository()
    val settings = InMemorySettingsRepository()
    val log = InMemoryActivityLog()
    val scanner = FakeAiAppScanner()
    val activity = ActivityRecorder(log, ids, clock)
    val deleted = mutableListOf<String>()
    val agentManager = DefaultAgentManager(agents, settings, activity, ids, clock, deletionListeners = listOf(AgentDeletionListener { deleted += it.name }))
    val appRegistry = DefaultAiAppRegistry(apps, scanner, activity, ids, clock)
    val directory = DefaultRegistryDirectory(agents, providers, models, apps, settings)
}
