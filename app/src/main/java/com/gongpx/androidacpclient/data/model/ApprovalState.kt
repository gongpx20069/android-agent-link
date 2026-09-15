package com.gongpx.androidacpclient.data.model

fun ApprovalStatus.isActionable(): Boolean =
    this == ApprovalStatus.Pending || this == ApprovalStatus.Submitting

fun BridgeApprovalRequest.toApproval(chat: Chat, nowMillis: Long): Approval = Approval(
    id = approvalId,
    chatId = chat.id,
    chatTitle = chat.title,
    machineId = chat.machineId,
    machineName = chat.machineName,
    workspacePath = chat.workspacePath,
    action = action,
    summary = summary,
    details = details,
    createdAtMillis = createdAtMillis.takeIf { it > 0 } ?: nowMillis,
    expiresAtMillis = expiresAtMillis,
)

fun Approval.resolve(status: String, decidedAt: Long): Approval {
    val resolved = when (status) {
        "approved" -> ApprovalStatus.Approved
        "denied" -> ApprovalStatus.Denied
        "expired" -> ApprovalStatus.Expired
        else -> ApprovalStatus.Unavailable
    }
    return copy(status = resolved, decidedAtMillis = decidedAt, error = null)
}

fun reconcileApprovalSnapshot(
    existing: List<Approval>,
    chat: Chat,
    pending: List<BridgeApprovalRequest>,
    nowMillis: Long,
): List<Approval> {
    val pendingById = pending.associateBy { it.approvalId }
    val updated = existing.map { approval ->
        if (approval.chatId != chat.id) approval
        else pendingById[approval.id]?.toApproval(chat, nowMillis)
            ?: if (approval.status.isActionable()) {
                approval.copy(status = ApprovalStatus.Unavailable, decidedAtMillis = nowMillis, error = null)
            } else approval
    }
    val known = existing.map { it.id }.toSet()
    return updated + pending.filterNot { it.approvalId in known }.map { it.toApproval(chat, nowMillis) }
}
