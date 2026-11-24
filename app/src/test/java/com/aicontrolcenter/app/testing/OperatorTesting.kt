package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.framework.agents.AgentTemplates

/*
 * Requests to the owner and an Operator agent on a scripted model, for the approval tests,
 * journeys and screenshots.
 */

/** The starter Operator, on a model served by [client]. Seeds the starter team first. */
suspend fun TestApp.operatorOn(client: LlmClient): AgentProfile {
    graph.start().join()
    val model = serveModel(client)
    val operator = agents.getAll().first { it.templateId == AgentTemplates.OPERATOR }
    graph.agentManager.assignModel(operator.id, model.id)
    return agents.get(operator.id)!!
}

/**
 * Stores [request] as the broker would raise it, with a live call waiting for it ([waiting]), or,
 * with [waiting] false, as an earlier run of the app left it: pending, with nothing waiting. The
 * broker raises requests only about a folder that exists or an app the owner authorised, so the
 * request's folder or app is added first when it is missing.
 */
suspend fun TestApp.raise(request: OwnerRequest, waiting: Boolean = true): OwnerRequest {
    when (val target = request.constraint) {
        is CapabilityConstraint.Folder -> if (sharedFolders.get(target.folderId) == null) {
            sharedFolders.upsert(SharedFolder(target.folderId, "content://test/tree/${target.folderId.value}", request.targetLabel ?: "Documents", request.createdAt))
        }
        is CapabilityConstraint.App -> if (operatorApps.get(target.packageName) == null) {
            operatorApps.upsert(OperatorApp(target.packageName, request.targetLabel ?: target.packageName, request.createdAt))
        }
        CapabilityConstraint.None -> Unit
    }
    if (waiting) graph.requestWaiters.register(request.id)
    ownerRequestStore.create(request)
    return request
}

/** The consequential action of the samples: deleting a folder's files, with a file name that hides a direction change. */
val HIDDEN_NAME: String = "Invoice‮fdp.exe"

/**
 * An APPROVAL request to delete files in a shared folder, as the folder tool raises it: model
 * values made exact (one file name hides a right-to-left override, one note has a line break).
 */
fun sampleApproval(
    agentId: AgentId,
    agentName: String = "Operator",
    id: String = "req_approval",
    createdAt: Long = TestApp.START_TIME,
    minutes: Int = 30,
    taskId: TaskId? = null,
    rootId: TaskId? = null,
    conversationId: ConversationId? = null,
    delegationChain: List<AgentId> = emptyList(),
) = OwnerRequest(
    id = RequestId(id),
    kind = OwnerRequestKind.APPROVAL,
    agentId = agentId,
    toolId = ToolId("folders.delete"),
    capability = OperatorCapabilities.FOLDERS_WRITE,
    constraint = CapabilityConstraint.Folder(FolderId("fld_documents")),
    risk = RiskLevel.CONSEQUENTIAL,
    summary = "Delete 3 files in Documents/Invoices: “${PreviewText.exact(HIDDEN_NAME)}”, “2024 Q4.pdf” and “notes.txt”",
    details = listOf(
        "Folder: Documents/Invoices",
        "Files: “${PreviewText.exact(HIDDEN_NAME)}” (18 KB), “2024 Q4.pdf” (240 KB), “notes.txt” (2 KB)",
        "notes.txt starts: “${PreviewText.exact("Paid in full.\nKeep for tax")}”",
        "Deleted files cannot be restored from this app.",
    ),
    taskId = taskId,
    rootId = rootId,
    conversationId = conversationId,
    createdAt = createdAt,
    expiresAt = createdAt + minutes * 60_000L,
    agentName = agentName,
    toolTitle = "Delete files in a shared folder",
    targetLabel = "Documents",
    delegationChain = delegationChain,
)

/** A PERMISSION request raised by a web page read inside a task: the agent needs "Read web pages". */
fun samplePermission(
    agentId: AgentId,
    agentName: String = "Researcher",
    id: String = "req_permission",
    createdAt: Long = TestApp.START_TIME,
    minutes: Int = 30,
    taskId: TaskId? = TaskId("tsk_compare"),
    rootId: TaskId? = TaskId("tsk_root"),
    conversationId: ConversationId? = null,
    reason: String? = null,
) = OwnerRequest(
    id = RequestId(id),
    kind = OwnerRequestKind.PERMISSION,
    agentId = agentId,
    toolId = ToolId("web.read_page"),
    capability = OperatorCapabilities.WEB_READ,
    risk = RiskLevel.SAFE,
    summary = "Read the web page “https://example.org/reviews/offline-notes”",
    details = listOf(
        "Needs the permission \"Read web pages\": Download public web pages as text. The website learns the address.",
        "Address: “https://example.org/reviews/offline-notes”",
        "The website example.org learns the address and this phone's network address.",
    ),
    dataGoesTo = "example.org",
    reason = reason,
    taskId = taskId,
    rootId = rootId,
    conversationId = conversationId,
    createdAt = createdAt,
    expiresAt = createdAt + minutes * 60_000L,
    agentName = agentName,
    toolTitle = "Read a web page",
)
