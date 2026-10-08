"""Deterministic ACP1 peer using Kimi/Qwen/DSH/OpenCode wire shapes; no model/network."""
import base64
import json
import sys

provider = sys.argv[1]
session_id = "native-session"
pending_prompt = None
options = [
    {"id": "model", "name": "Model", "type": "select", "category": "model",
     "currentValue": "model-one", "options": [{"group": "provider", "name": "Provider", "options": [
         {"value": "model-one", "name": "One"}, {"value": "model-two", "name": "Two"}]}]},
    {"id": "mode", "name": "Mode", "type": "select", "category": "mode",
     "currentValue": "default", "options": [{"value": "default", "name": "Default"}, {"value": "plan", "name": "Plan"}]},
]
if provider == "deepseek-harness":
    options[1] = {"id": "reasoning_effort", "name": "Reasoning", "type": "select",
                  "category": "thought_level", "currentValue": "high",
                  "options": [{"value": "high", "name": "High"}, {"value": "max", "name": "Max"}]}
if provider == "opencode":
    options[0]["currentValue"] = "provider/model-one"
    options[0]["options"] = [
        {"value": "provider/model-one", "name": "Provider/One"},
        {"value": "provider/model-two", "name": "Provider/Two"},
    ]
    options.append({"id": "effort", "name": "Effort", "type": "select", "category": "thought_level",
                    "currentValue": "default",
                    "options": [{"value": "default", "name": "Default"}, {"value": "high", "name": "High"}]})


def send(value):
    print(json.dumps({"jsonrpc": "2.0", **value}), flush=True)


def update(kind, **fields):
    send({"method": "session/update", "params": {
        "sessionId": session_id, "update": {"sessionUpdate": kind, **fields}}})


def reverse(method, params):
    send({"id": "interaction", "method": method, "params": {"sessionId": session_id, **params}})
    answer = json.loads(sys.stdin.readline())
    assert answer["id"] == "interaction"
    return answer["result"]


for line in sys.stdin:
    message = json.loads(line)
    method, params = message["method"], message.get("params", {})
    result = {}
    if method == "initialize":
        expected = {"elicitation": {"form": {}}} if provider == "kimi-cli" else {}
        assert params["clientCapabilities"] == expected
        result = {"protocolVersion": 1, "agentInfo": {"name": provider, "version": "fixture"},
                  "agentCapabilities": {"loadSession": provider != "deepseek-harness",
                                        "promptCapabilities": {"image": provider == "opencode"},
                                        "sessionCapabilities": {"list": {}, "resume": {}, "close": {}}}}
    elif method == "session/list":
        result = {"sessions": [{"sessionId": "second" if params.get("cursor") else session_id, "cwd": params["cwd"]}]}
        if not params.get("cursor"):
            result["nextCursor"] = "next"
    elif method in {"session/new", "session/load", "session/resume"}:
        if provider == "deepseek-harness":
            assert method != "session/load", "DSH cannot replay history"
            if params.get("sessionId") == "missing":
                send({"id": message["id"], "error": {"code": -32602, "message": "Session not found"}})
                continue
            session_id = params.get("sessionId", session_id)
        if method == "session/load":
            update("user_message_chunk", content={"type": "text", "text": "saved user"})
            update("agent_message_chunk", content={"type": "text", "text": "saved answer"})
        result = {"sessionId": session_id, "configOptions": options}
        if provider == "opencode" and method != "session/new":
            result.pop("sessionId")
    elif method == "session/set_config_option":
        option = next(option for option in options if option["id"] == params["configId"])
        option["currentValue"] = params["value"]
        result = {"configOptions": options}
    elif method == "session/prompt":
        if provider == "opencode" and params["prompt"][0]["text"] == "auth error":
            send({"id": message["id"], "error": {"code": -32000, "message": "Authentication required"}})
            continue
        if provider == "opencode" and params["prompt"][0]["text"] == "image":
            assert params["prompt"][1] == {
                "type": "image", "mimeType": "image/png",
                "data": base64.b64encode(b"image-bytes").decode("ascii"),
            }
            permission = reverse("session/request_permission", {
                "toolCall": {"toolCallId": "edit", "title": "Edit file", "kind": "edit",
                             "rawInput": {"filepath": "file.txt"},
                             "content": [{"type": "diff", "path": "file.txt", "oldText": "old", "newText": "new"}]},
                "options": [{"optionId": "once", "kind": "allow_once", "name": "Allow once"},
                            {"optionId": "always", "kind": "allow_always", "name": "Always allow"},
                            {"optionId": "reject", "kind": "reject_once", "name": "Reject"}],
            })
            allowed = permission["outcome"].get("optionId") in {"once", "always"}
            update("tool_call_update", toolCallId="edit", status="completed",
                   rawOutput={"permissionOutcome": permission["outcome"]})
            update("agent_message_chunk", content={"type": "text", "text": "allowed" if allowed else "rejected"})
            send({"id": message["id"], "result": {"stopReason": "end_turn"}})
            continue
        if params["prompt"][0]["text"] == "wait for cancel":
            pending_prompt = message["id"]
            update("agent_thought_chunk", content={"type": "text", "text": "working"})
            continue
        allow = "allow-once" if provider == "deepseek-harness" else "once"
        permission = reverse("session/request_permission", {
            "toolCall": {"toolCallId": "edit", "title": "Edit file"},
            "options": [{"optionId": "reject-once", "name": "Reject", "kind": "reject_once"},
                        {"optionId": allow, "name": "Once", "kind": "allow_once"}],
        })
        assert permission["outcome"] == {"outcome": "selected", "optionId": allow}
        if provider == "kimi-cli":
            answer = reverse("elicitation/create", {
                "mode": "form", "message": "Features?",
                "requestedSchema": {"type": "object", "properties": {
                    "q0": {"type": "array", "minItems": 1, "items": {"anyOf": [{"const": "A"}, {"const": "B"}]}}},
                    "required": ["q0"]},
            })
            assert answer == {"action": "accept", "content": {"q0": ["A", "B"]}}
        elif provider == "qwen-code":
            answer = reverse("session/request_permission", {
                "toolCall": {"toolCallId": "ask", "title": "Question", "_meta": {
                    "qwenInteractionKind": "user_question", "qwenQuestions": [
                        {"question": "Features?", "header": "Features", "multiSelect": True,
                         "options": [{"label": "A", "description": "First"}, {"label": "B", "description": "Second"}]}]}},
                "options": [{"optionId": "proceed_once", "name": "Submit", "kind": "allow_once"},
                            {"optionId": "cancel", "name": "Cancel", "kind": "reject_once"}],
            })
            assert answer == {"outcome": {"outcome": "selected", "optionId": "proceed_once"}, "answers": {"0": "A, B"}}
        update("agent_message_chunk", content={"type": "text", "text": "done"})
        result = {"stopReason": "end_turn"}
    elif method == "session/cancel":
        send({"id": pending_prompt, "result": {"stopReason": "cancelled"}})
        continue
    else:
        send({"id": message["id"], "error": {"code": -32601, "message": "Unsupported method"}})
        continue
    send({"id": message["id"], "result": result})
