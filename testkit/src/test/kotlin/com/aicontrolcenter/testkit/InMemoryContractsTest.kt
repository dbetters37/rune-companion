package com.aicontrolcenter.testkit

import com.aicontrolcenter.testkit.contract.ActivityLogContract
import com.aicontrolcenter.testkit.contract.AgentRepositoryContract
import com.aicontrolcenter.testkit.contract.AiAppRepositoryContract
import com.aicontrolcenter.testkit.contract.ModelRepositoryContract
import com.aicontrolcenter.testkit.contract.ProviderRepositoryContract
import com.aicontrolcenter.testkit.contract.SecretVaultContract
import com.aicontrolcenter.testkit.contract.SettingsRepositoryContract

class InMemoryAgentRepositoryTest : AgentRepositoryContract() {
    override fun createRepository() = InMemoryAgentRepository()
}

class InMemoryProviderRepositoryTest : ProviderRepositoryContract() {
    override fun createRepository() = InMemoryProviderRepository()
}

class InMemoryModelRepositoryTest : ModelRepositoryContract() {
    override fun createRepository() = InMemoryModelRepository()
}

class InMemoryAiAppRepositoryTest : AiAppRepositoryContract() {
    override fun createRepository() = InMemoryAiAppRepository()
}

class InMemoryActivityLogTest : ActivityLogContract() {
    override fun createLog() = InMemoryActivityLog()
}

class InMemorySettingsRepositoryTest : SettingsRepositoryContract() {
    override fun createRepository() = InMemorySettingsRepository()
}

class InMemorySecretVaultTest : SecretVaultContract() {
    override fun createVault() = InMemorySecretVault()
}
