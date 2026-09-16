# AgentLink

[English](README.md) | [中文](README.zh-CN.md)

**离开电脑，也能在手机上继续和 coding agent 协作。**

AgentLink 把远程电脑上的编程助手会话带到 Android。你可以发起任务、查看进展、
响应审批请求，或接着之前的会话继续工作。项目文件和 agent 进程仍然留在电脑上。

**[下载 Android APK](https://github.com/gongpx20069/android-agent-link/releases)**

## 你可以用它做什么

- **管理不同电脑和项目的会话。** 为每个 Chat 选择电脑、项目目录和 agent。
- **随时查看任务进展。** 阅读流式回复、工具活动和执行结果。
- **在手机上响应请求。** 批准或拒绝 agent 发来的审批请求。
- **接着上次的工作继续。** 恢复 agent 提供的已有会话，查看最近历史。

AgentLink 是远程控制端，不会在手机上运行编程 agent。使用时，电脑需要保持唤醒、
联网，并运行 bridge。

## 使用前准备

| 手机上 | 电脑上 |
| --- | --- |
| Android 8.0 或更高版本，安装 AgentLink APK | Python 3.11 或更高版本、Git，以及已安装并登录的编程 agent CLI |
| 可以访问互联网 | 可以访问互联网，并保持 AgentLink bridge 运行 |

**最推荐使用 Microsoft Dev Tunnels，这也是默认连接方式。**
它通过需要身份认证的中继连接手机和电脑，无需开放电脑的入站防火墙端口，
也无需在 Android 上额外安装 VPN 或组网 App。请始终保持隧道为私有认证访问，
不要启用匿名访问。

## 快速开始

以下电脑端命令适用于 Windows PowerShell。

### 1. 在手机上安装 AgentLink

打开 [Releases](https://github.com/gongpx20069/android-agent-link/releases)，
从最新发布的版本下载 `agentlink-0.0.x.apk`。当前版本标记为 **Pre-release（预发布）**。
在 Android 上打开 APK；如有提示，允许从当前来源安装。

签名发布版可以覆盖升级之前的签名发布版。如果之前安装的是 debug 包，切换到发布版时
可能需要先卸载；卸载会删除该 App 的本地数据。

### 2. 首次配置电脑

先安装并登录你要使用的 agent CLI，再下载 bridge：

```powershell
git clone https://github.com/gongpx20069/android-agent-link.git
cd android-agent-link
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .\bridge
```

已经下载过仓库的话，直接使用已有目录，不必重复克隆。
Conda、uv 等其他安装方式请参考 [bridge 指南](bridge/README.md)。

### 3. 启动推荐的 Dev Tunnel 连接

在仓库目录运行：

```powershell
.\.venv\Scripts\python.exe .\bridge\run.py start
```

如果提示登录，按终端说明完成 Dev Tunnels 登录。bridge 会为这台电脑准备私有隧道，
并输出配对二维码和链接。请保持这个终端运行。

Windows 上缺少 Dev Tunnels CLI 时，bridge 可以自动下载。
使用已发布的 AgentLink App，不需要你自己注册应用或输入 Client ID。

### 4. 配对手机

**扫描二维码**

在 AgentLink 中进入 **Machines → Scan QR**，扫描终端里的二维码，
然后在电脑上确认配对。也可以粘贴终端输出的配对链接。
保存后，点击电脑卡片上的 **Test Connection** 检查连接。

**也可以尝试登录账号发现电脑，无需扫码**

`0.0.25` 新增了账号发现功能。电脑和手机必须使用相同的登录方式及账号。
例如，电脑端使用 GitHub：

```powershell
.\.venv\Scripts\python.exe .\bridge\run.py start --devtunnel-login github
```

在手机 **Machines** 页面选择 GitHub 登录，按提示通过浏览器和设备代码完成授权，
然后查找电脑。选中电脑后，核对手机和电脑显示的六位确认码，
在电脑终端输入该确认码，批准首次访问。
GitHub 授权页显示的应用名称是微软的 **Visual Studio Tunnel Service**。

之后启动时可省略登录参数，复用电脑 CLI 已有的账号。

**Microsoft 手机账号发现暂时停用。** 个人账号实测中，基础登录可以成功，
但隧道授权请求会出现误导性的“代码过期”错误。无需自行注册应用来解决。
电脑上的 Dev Tunnels CLI 仍可使用 Microsoft 登录，再通过二维码配对手机。

**预览功能限制：** GitHub 尚未完成真实手机上的登录、发现、配对全流程验证。
账号发现不可用时，请使用二维码配对。

### 5. 开始第一个会话

进入 **Chats → New Chat**，选择电脑和 agent，然后填写项目在**电脑上**的绝对路径，
例如 `C:\Repos\my-project`。创建会话后，就可以发送任务。

想继续之前的工作，可以在 New Chat 中选择 **Existing session**，加载 agent 提供的会话。
agent 发起审批请求时，在 **Approvals** 中处理。
可恢复的会话、可用模型和权限行为取决于电脑上安装的 agent CLI。

## 支持的编程助手

| Agent | AgentLink 接入情况 |
| --- | --- |
| GitHub Copilot CLI | 主要测试路径；电脑上需要能够运行 `copilot --acp`。 |
| Claude Code | 安装 `claude` 后可发现；需要实际支持 `claude --acp` 的版本或配置。 |

其他 agent 尚未接入。CLI 出现在电脑的 agent 列表中，只代表发现了安装，
不代表已经确认它兼容 ACP。

## 关于持续连接

账号配对的连接会在需要时获取新的隧道连接凭据，前提是账号仍有访问权限。
GitHub 凭据过期或被撤销后需要重新登录。
这不等于永久在线，也不保证电脑端登录能够无人值守地续期。

- 电脑需要保持唤醒，bridge 需要持续运行。
- 二维码配对保存的隧道凭据过期后，可能需要重启 bridge 并扫描新码。
- 电脑报告 Dev Tunnels 登录过期时，请执行错误提示中的登录命令，再重启 bridge。
- Android 后台监控目前有一小时限制，不是永久在线服务。

## 其他连接方式

优先使用 Dev Tunnels。如果你已经在使用 Tailscale，也可以运行：

```powershell
.\.venv\Scripts\python.exe .\bridge\run.py start --transport tailscale
```

手机和电脑都需要安装 Tailscale、登录同一个 tailnet，并保持连接，
再通过 bridge 的二维码配对。AgentLink 暂不自动配置 ZeroTier。
本地连接模式仅用于测试，不会自动让手机从外网访问电脑。

## 常见问题

| 问题 | 处理方式 |
| --- | --- |
| 提示找不到 `android-acp-bridge` | 在仓库目录使用上面的 `.\.venv\Scripts\python.exe .\bridge\run.py start` 命令。 |
| 手机连接不上 | 检查电脑是否唤醒、bridge 是否运行；二维码连接的凭据过期时，重新获取二维码。 |
| 登录后找不到电脑 | 更新并重启 bridge，检查两端的登录方式和账号是否一致；GitHub 与 Microsoft 是不同的身份。 |
| Dev Tunnels 提示 `Login token expired` | 执行 bridge 错误提示中的完整 CLI 登录命令，再重启；手机登录不会刷新电脑端登录。 |
| 手机取消配对后，电脑仍等待输入 | 在电脑按 Enter 结束未完成的确认提示，再重试。 |
| Agent 不可用或启动失败 | 在电脑上安装、登录对应 CLI，并检查其 ACP 命令能否正常运行。 |

更新 bridge 时，先停止进程，在仓库目录运行 `git pull --ff-only`，
重新执行上面的 pip 安装命令，再启动。新功能需要使用匹配的 App 和 bridge 版本。
旧安装如需保留固定隧道地址，可添加 `--devtunnel-id agentlink`；
否则重新发现或配对新的电脑隧道。

更多安装和排查方法见 [bridge 指南](bridge/README.md)。
反馈问题时，请在 [GitHub Issues](https://github.com/gongpx20069/android-agent-link/issues)
提供 App 版本及错误提示，不要附带配对链接、登录代码或令牌。

## 参与开发

如需构建或扩展 AgentLink，请阅读[技术文档](docs/README.md)和
[贡献者说明](CLAUDE.md)。
