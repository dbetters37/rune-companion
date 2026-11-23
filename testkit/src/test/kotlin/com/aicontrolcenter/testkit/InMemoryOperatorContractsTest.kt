package com.aicontrolcenter.testkit

import com.aicontrolcenter.testkit.contract.CapabilityRequesterContract
import com.aicontrolcenter.testkit.contract.OperatorAppRepositoryContract
import com.aicontrolcenter.testkit.contract.OwnerPortsContract
import com.aicontrolcenter.testkit.contract.OwnerRequestRepositoryContract
import com.aicontrolcenter.testkit.contract.PermissionRepositoryContract
import com.aicontrolcenter.testkit.contract.RequestStores
import com.aicontrolcenter.testkit.contract.SharedFolderRepositoryContract

class InMemoryPermissionRepositoryTest : PermissionRepositoryContract() {
    override fun createRepository() = InMemoryPermissionRepository()
}

class InMemoryOwnerRequestRepositoryTest : OwnerRequestRepositoryContract() {
    override fun createStores(): RequestStores {
        val grants = InMemoryPermissionRepository()
        return RequestStores(InMemoryOwnerRequestRepository(grants), grants)
    }
}

class InMemorySharedFolderRepositoryTest : SharedFolderRepositoryContract() {
    override fun createRepository() = InMemorySharedFolderRepository()
}

class InMemoryOperatorAppRepositoryTest : OperatorAppRepositoryContract() {
    override fun createRepository() = InMemoryOperatorAppRepository()
}

class ReferenceCapabilityRequesterTest : CapabilityRequesterContract() {
    override fun createRequester(world: OperatorWorld) = ReferenceCapabilityRequester(world)
}

class ReferenceOwnerPortsTest : OwnerPortsContract() {
    override fun createGrants(world: OperatorWorld) = ReferenceOwnerGrants(world)
    override fun createFolders(world: OperatorWorld) = ReferenceOwnerFolders(world)
    override fun createApps(world: OperatorWorld) = ReferenceOwnerOperatorApps(world)
}
