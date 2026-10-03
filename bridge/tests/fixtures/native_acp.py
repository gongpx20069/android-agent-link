"""Deterministic ACP1 peer using published Kimi/Qwen/DSH wire shapes; no model/network."""
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
    elif method == "session/set_config_option":
        option = next(option for option in options if option["id"] == params["configId"])
        option["currentValue"] = params["value"]
        result = {"configOptions": options}
    elif method == "session/prompt":
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
