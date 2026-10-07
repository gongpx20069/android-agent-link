package com.gongpx.androidacpclient.data.model

import com.gongpx.androidacpclient.data.tunnel.TunnelBinding

data class PairingPayload(
    val version: Int,
    val type: String,
    val machineName: String,
    val endpoint: String,
    val pairingId: String,
    val pairingToken: String,
    val expiresAt: String,
    val bridgeFingerprint: String,
    val headers: Map<String, String> = emptyMap(),
)

data class Machine(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val deviceToken: String,
    val bridgeFingerprint: String,
    val connectionHeaders: Map<String, String> = emptyMap(),
    val bridgeVersion: String? = null,
    val connectionState: ConnectionState = ConnectionState.Unknown,
    val workspaces: List<Workspace> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val tunnelBinding: TunnelBinding? = null,
)

data class Workspace(
    val id: String,
    val displayName: String,
    val absolutePath: String,
)

data class Agent(
    val id: String,
    val displayName: String,
    val status: String,
    val statusMessage: String? = null,
)

enum class ConnectionState {
    Unknown,
    Online,
    Offline,
}

data class Chat(
    val id: String,
    val title: String,
    val machineId: String,
    val machineName: String,
    val workspaceId: String,
    val workspaceName: String,
    val workspacePath: String,
    val agentId: String,
    val agentName: String,
    val createdAtMillis: Long,
    val acpSessionId: String? = null,
    val acpSessionResumable: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val lastBridgeEventId: Int = 0,
    val bridgeEventGeneration: String? = null,
    val bridgeResyncRequired: Boolean = false,
    val agentStatus: String = "unknown",
    val lastSyncAtMillis: Long = 0,
    val connectionError: String? = null,
    val historyId: String? = null,
    val historyNextBefore: Int? = null,
    val historyHasMore: Boolean = false,
    val historyTotalMessages: Int = 0,
    val lastNotifiedOperationId: String? = null,
    val timelineId: String = id,
    val localHistoryBefore: Long? = null,
    val historyReplaySupported: Boolean = true,
    val activePromptImages: Map<String, ImageAttachment> = emptyMap(),
)

data class QueuedPrompt(
    val operationId: String,
    val text: String,
    val createdAtMillis: Long,
    val removing: Boolean = false,
    val image: ImageAttachment? = null,
)

data class AvailableCommand(
    val name: String,
    val description: String,
    val inputHint: String? = null,
)

data class ConfigOption(
    val id: String,
    val name: String,
    val category: String?,
    val type: String,
    val currentValue: String?,
    val options: List<ConfigOptionValue> = emptyList(),
)

data class ConfigOptionValue(
    val value: String,
    val name: String,
    val description: String?,
)

data class AgentSessionInfo(
    val sessionId: String,
    val title: String?,
    val cwd: String?,
    val updatedAt: String?,
    val historyReplaySupported: Boolean = true,
)

data class BridgeApprovalRequest(
    val approvalId: String,
    val action: String,
    val summary: String,
    val details: String?,
    val createdAtMillis: Long = 0,
    val expiresAtMillis: Long? = null,
    val options: List<ApprovalOption> = emptyList(),
    val interaction: String = "permission",
    val requestedSchema: String? = null,
)

data class ApprovalOption(val optionId: String, val name: String, val kind: String)

data class ApprovalAnswer(
    val status: ApprovalStatus,
    val optionId: String? = null,
    val content: String? = null,
)

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val timestampMillis: Long,
    val kind: ChatMessageKind = ChatMessageKind.Message,
    val title: String? = null,
    val details: String? = null,
    val activityId: String? = null,
    val operationId: String? = null,
    val localId: String = java.util.UUID.randomUUID().toString(),
    val isToolSnapshot: Boolean = false,
    val image: ImageAttachment? = null,
)

enum class MessageRole {
    User,
    Agent,
    System,
}

enum class ChatMessageKind {
    Message,
    Activity,
    Plan,
    CommandUpdate,
    ConfigUpdate,
}

data class Approval(
    val id: String,
    val chatId: String,
    val chatTitle: String,
    val machineId: String,
    val machineName: String,
    val workspacePath: String,
    val action: String,
    val summary: String,
    val status: ApprovalStatus = ApprovalStatus.Pending,
    val createdAtMillis: Long,
    val details: String? = null,
    val expiresAtMillis: Long? = null,
    val decidedAtMillis: Long? = null,
    val error: String? = null,
    val options: List<ApprovalOption> = emptyList(),
    val interaction: String = "permission",
    val requestedSchema: String? = null,
    val agentName: String = "",
)

enum class ApprovalStatus {
    Pending,
    Submitting,
    Approved,
    Denied,
    Expired,
    Unavailable,
}
