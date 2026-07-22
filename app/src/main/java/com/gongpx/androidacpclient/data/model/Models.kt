package com.gongpx.androidacpclient.data.model

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
)

data class QueuedPrompt(
    val operationId: String,
    val text: String,
    val createdAtMillis: Long,
    val removing: Boolean = false,
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
)

data class BridgeApprovalRequest(
    val approvalId: String,
    val action: String,
    val summary: String,
    val details: String?,
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
)

enum class ApprovalStatus {
    Pending,
    Approved,
    Denied,
}
