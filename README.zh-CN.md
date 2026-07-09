# AgentLink

[English](README.md) | [中文](README.zh-CN.md)

AgentLink 可以让你在 Android 手机上控制远程开发机上的 coding agent。开发机运行一个 bridge 和 agent CLI；手机负责 Chat、Approval、恢复会话、切换模型、查看 agent 更新。

## 你需要准备什么

- 一台 Android 手机。
- 一台已经 checkout 本仓库的开发机。
- 开发机上安装 coding agent。目前主要路径是支持 ACP 的 GitHub Copilot CLI。
- 一种私有连接方式：
  - **Microsoft Dev Tunnels**：VPN / mesh 网络不可用时最方便。
  - **Tailscale**：可用时体验最好。

AgentLink 不要求开发机开放入站端口。

## 安装 Android app

到本仓库的 **Releases** 页面下载最新 APK，然后在 Android 上安装。

如果你之前安装过 debug 版本，第一次切换到 signed release APK 时，Android 可能要求先卸载旧版本。之后 signed release APK 可以原地升级。

## 启动 bridge

请在开发机的仓库根目录运行 bridge 命令。

### 方式 A：Microsoft Dev Tunnels

如果不能使用 Tailscale / ZeroTier，但可以登录 Microsoft Dev Tunnels，推荐用这个方式：

```powershell
python .\bridge\run.py start --transport devtunnel
```

bridge 会自动：

1. 查找 `devtunnel`，Windows 上找不到时下载 `bridge\.tools\devtunnel.exe`。
2. 如果尚未登录，启动 device-code login。
3. 创建或复用私有的 `agentlink` tunnel。
4. 启动 tunnel host 和本地 bridge。
5. 输出 Android pairing link 和 QR code。

不要启用 anonymous / public Dev Tunnel。AgentLink 会把短期 `X-Tunnel-Authorization` token 放入配对 QR。

### 方式 B：Tailscale

如果手机和开发机都可以加入同一个 Tailscale tailnet，使用：

```powershell
python .\bridge\run.py start
```

bridge 会检查 Tailscale，必要时运行 `tailscale up --qr`，等待 Tailscale IP 可用，然后输出 pairing link 和 QR code。

如果 Windows 因组织策略或 exit code `1625` 阻止安装 Tailscale，请通过公司软件门户安装，或让管理员批准 `Tailscale.Tailscale`，也可以使用官方安装包：<https://tailscale.com/download/windows>。

### 仅本地测试

```powershell
python .\bridge\run.py start --transport local
```

这个方式只适合本地/手动测试。它不会让手机自动访问到开发机。

## 配对开发机

1. 在 Android 上打开 AgentLink。
2. 进入 **Machines**。
3. 点击 **Scan QR**。
4. 扫描 bridge 输出的 QR。
5. 在开发机上确认配对。
6. 点击 **Test Connection** 确认连接可用。

QR 中包含短期 pairing token。如果过期，重新运行 bridge 命令并再次扫码。

## 开始一个 Chat

1. 进入 **Chats**。
2. 点击 **New Chat**。
3. 选择已配对的 Machine。
4. 选择 Agent，例如 Copilot CLI。
5. 输入远端 workspace 路径，例如：

```text
C:\Repos\my-project
```

6. 创建 Chat，然后发送 prompt。

AgentLink 会显示 streaming 回复、tool activity、model / command chips，以及需要你决策的 Approval。

## 恢复已有 session

在 **New Chat** 中切换到 **Existing session**，从选中的 Machine / Agent 加载可恢复 sessions，然后打开一个 session。

AgentLink 会让 bridge 返回“最近历史快照”，不会把完整旧历史一条条刷到 UI。最近消息数量可以在 **Settings** 中调整。

## Approval

当 agent 请求执行命令或高风险操作时，AgentLink 会在 **Approvals** tab 显示请求。你可以在手机上批准或拒绝；bridge 只有收到决策后才会继续。

## 常见问题

### 扫码后 Android 连不上

- 确认 bridge 进程仍在运行。
- 如果使用 Dev Tunnels，connect token 过期后需要重新运行 bridge 并扫码。
- 如果使用 Tailscale，确认 Android 和开发机在同一个 tailnet。
- 检查 Machine card 里的 endpoint 是否符合预期。

### Dev Tunnel 登录或创建失败

运行：

```powershell
.\bridge\.tools\devtunnel.exe user login -d
```

或者如果 `devtunnel` 在 PATH 上：

```powershell
devtunnel user login -d
```

然后重试：

```powershell
python .\bridge\run.py start --transport devtunnel
```

### Copilot agent 不可用

在开发机上安装并登录 GitHub Copilot CLI，然后确认这个命令可用：

```powershell
copilot --acp
```

## 给贡献者

这个 README 是给用户看的。开发和 agent 工作规则在：

- `CLAUDE.md`
- `docs/README.md`
- `docs/architecture.md`
- `docs/acp-bridge-contract.md`
- `docs/android-app.md`
- `docs/security-model.md`

