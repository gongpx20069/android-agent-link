# AgentLink

[English](README.md) | [中文](README.zh-CN.md)

AgentLink 是一个 Android-first 的 ACP client，用来在手机上控制多台远程开发机器上的 coding agents。

App 以 **Chats** 为核心。每个 Chat 绑定一台远程 **Machine** 和该机器上的一个 **Workspace**。远程 Machine 运行 ACP bridge 和一个或多个 coding agents；Android app 负责移动端交互，包括 prompt、streaming response、Approval、diff 和 logs。

## 当前状态

当前 repository 包含：

- Python bridge MVP：`bridge/`
- Android app：`app/`
- GitHub Actions prechecks 和 APK build workflow
- Tailscale / Microsoft Dev Tunnels 配对流程
- GitHub Copilot CLI ACP 集成
- Chats、Approvals、Machines、Settings 基础 UI
- app 内更新检查
- 中英双语 UI，可在 Settings 中选择 System / English / 中文

## 启动 bridge

请在 repository root 运行 bridge 命令。`bridge\run.py` 会创建 `bridge\.venv`，安装 `bridge\requirements.txt`，并启动 bridge CLI。

### 方式 1：Tailscale 私有网络（默认）

当开发机器和 Android 设备都可以登录同一个 Tailscale tailnet 时，使用：

```powershell
python .\bridge\run.py start
```

启动流程：

1. 检查是否存在 `tailscale` CLI。
2. 如未安装，尝试通过受支持的 package manager 自动安装。
3. 如未登录或未启动，运行 `tailscale up --qr`。
4. 等待 Tailscale IP 可用。
5. 将 bridge 绑定到 Tailscale IP。
6. 输出 AgentLink pairing link 和 CLI QR code。

如果 Windows 因组织策略或 exit code `1625` 阻止 `winget`，请通过公司软件门户安装 Tailscale，或请求管理员批准 `Tailscale.Tailscale`，也可以使用 <https://tailscale.com/download/windows> 的官方安装包。

### 方式 2：Microsoft Dev Tunnels 私有 relay

当 VPN / mesh networking 不可用，但可以使用经过身份验证的 Microsoft Dev Tunnel 时，使用：

```powershell
python .\bridge\run.py start --transport devtunnel
```

请不要使用 anonymous / public tunnel。

该命令会自动完成 tunnel setup、启动 Dev Tunnel host、启动本地 bridge，并输出 Android pairing QR。

### 方式 3：Localhost / manual testing

仅用于本地实验：

```powershell
python .\bridge\run.py start --allow-non-tailscale
```

QR pairing 只传递 endpoint metadata 和 credentials；手机是否能访问开发机仍取决于 Tailscale、Dev Tunnels、LAN、USB forwarding 或其他 transport。

## Android app

App 支持：

- Machine QR pairing
- New Chat：创建新的 ACP session
- Existing session：从远端可恢复 session 列表中选择并打开
- Chat detail streaming
- command chips，包括 `model`、`resume`、`allow-all`
- Approval workflow
- 左滑删除 Chats、Approvals、Machines
- Settings 中切换语言：System / English / 中文
- Settings 中检查 release update

语言默认跟随系统：系统语言为中文时显示中文，其他语言显示 English。AgentLink、ACP、Chat、Machine、Workspace、Agent、Approval 等专有词汇会保留英文。

## APK release

手动运行 **Build Android APK** GitHub Actions workflow 会构建 signed release APK，并创建下一个 `0.0.x` prerelease。

Android 更新要求新 APK 与已安装 app 使用同一个签名密钥。首次从 debug APK 切换到 signed release APK 时，可能需要先卸载旧 debug 版；之后 signed release APK 可以原地升级。
