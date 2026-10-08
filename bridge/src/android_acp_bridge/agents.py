from __future__ import annotations

import shutil
from dataclasses import dataclass


@dataclass(frozen=True)
class AgentInfo:
    id: str
    display_name: str
    status: str
    status_message: str = ""

    def to_wire(self) -> dict[str, str]:
        return {"id": self.id, "displayName": self.display_name, "status": self.status,
                "statusMessage": self.status_message}


@dataclass(frozen=True)
class AgentSpec:
    name: str
    command: str
    package: str | None
    arguments: tuple[str, ...]
    requirements: str
    missing_status: str = "missing"


AGENT_SPECS = {
    "claude-code": AgentSpec("Claude Code", "claude-agent-acp", "@agentclientprotocol/claude-agent-acp", (),
                            "Install @agentclientprotocol/claude-agent-acp@0.81.2 (Node.js 22+) and sign in to Claude on this machine.",
                            "missing_adapter"),
    "copilot-cli": AgentSpec("GitHub Copilot CLI", "copilot", None, (), ""),
    "kimi-cli": AgentSpec("Kimi Code", "kimi", "@moonshot-ai/kimi-code", ("acp",),
                         "Install @moonshot-ai/kimi-code@2.1.1 (Node.js 22.19+) and run kimi login on this machine. The archived Python kimi-cli is not the supported baseline."),
    "qwen-code": AgentSpec("Qwen Code", "qwen", "@qwen-code/qwen-code", ("--acp",),
                          "Install @qwen-code/qwen-code@0.24.6 (Node.js 22+) and configure authentication locally with qwen."),
    "deepseek-harness": AgentSpec("DeepSeek Harness", "dsh", "@deepseek-ai/dsh", ("--profile", "acp"),
                                 "Install @deepseek-ai/dsh@0.1.7-rc.2 (Node.js 24 recommended) and configure the provider locally. Context resume is supported; ACP history replay is not."),
    "opencode": AgentSpec("OpenCode", "opencode", "opencode-ai",
                         ("acp", "--hostname", "127.0.0.1", "--port", "0", "--mdns=false"),
                         "Install OpenCode (1.18.35 baseline) and run opencode auth login on this machine. Configure permission rules to ask for operations you want to review; OpenCode allows most operations by default."),
}


def discover_agents() -> list[AgentInfo]:
    return [AgentInfo(identity, spec.name,
                      "available" if shutil.which(spec.command) else spec.missing_status,
                      spec.requirements)
            for identity, spec in AGENT_SPECS.items()]
