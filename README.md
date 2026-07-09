# AgentLink

[English](README.md) | [中文](README.zh-CN.md)

AgentLink lets you control remote coding agents from an Android phone. Your developer machine runs a small bridge and the agent CLI; your phone becomes the control surface for chats, approvals, session resume, model selection, and agent updates.

## What you need

- An Android phone.
- A developer machine with this repository checked out.
- A coding agent installed on the developer machine. GitHub Copilot CLI with ACP support is currently the primary path.
- One private connection method:
  - **Microsoft Dev Tunnels**: easiest when VPN/mesh networking is blocked.
  - **Tailscale**: best direct private-network experience when available.

AgentLink does not require opening inbound firewall ports on your developer machine.

## Install the Android app

Download the latest APK from this repository's **Releases** page and install it on Android.

If you previously installed a debug build, Android may require uninstalling it once before installing the signed release APK. After that, signed releases can update in place.

## Start the bridge

Run bridge commands from the repository root on your developer machine.

### Option A: Microsoft Dev Tunnels

Use this when you cannot install or use Tailscale/ZeroTier, but you can sign in to Microsoft Dev Tunnels.

```powershell
python .\bridge\run.py start --transport devtunnel
```

The bridge will:

1. Find `devtunnel` or download `bridge\.tools\devtunnel.exe` on Windows.
2. Ask you to sign in with device-code login if needed.
3. Create or reuse a private `agentlink` tunnel.
4. Start the tunnel host and local bridge.
5. Print a pairing link and QR code for Android.

Do **not** enable anonymous/public Dev Tunnel access. AgentLink uses a short-lived `X-Tunnel-Authorization` token in the pairing QR.

### Option B: Tailscale

Use this when your phone and developer machine can join the same Tailscale tailnet.

```powershell
python .\bridge\run.py start
```

The bridge checks Tailscale, runs `tailscale up --qr` if login is needed, waits for a Tailscale IP, then prints a pairing link and QR code.

If Windows blocks Tailscale install with organization policy / exit code `1625`, install Tailscale through your company software portal, ask an administrator to approve `Tailscale.Tailscale`, or use the official installer from <https://tailscale.com/download/windows>.

### Local testing only

```powershell
python .\bridge\run.py start --transport local
```

This is only useful for local/manual tests. It does not make your developer machine reachable from your phone by itself.

## Pair a machine

1. Open AgentLink on Android.
2. Go to **Machines**.
3. Tap **Scan QR**.
4. Scan the QR printed by the bridge.
5. Confirm pairing on the developer machine when prompted.
6. Use **Test Connection** to verify the bridge is reachable.

The QR contains a short-lived pairing token. If it expires, restart or re-run the bridge command and scan again.

## Start a chat

1. Go to **Chats**.
2. Tap **New Chat**.
3. Select the paired machine.
4. Select an agent, such as Copilot CLI.
5. Enter the remote workspace path, for example:

```text
C:\Repos\my-project
```

6. Create the chat and send a prompt.

AgentLink shows streaming agent replies, tool activity cards, model/command chips, and approval requests.

## Resume an existing session

In **New Chat**, switch to **Existing session**, load sessions from the selected machine/agent, and open one.

AgentLink asks the bridge for a recent-history snapshot instead of showing the full old replay. The number of recent messages can be adjusted in **Settings**.

## Approvals

When the agent requests permission for a command or risky operation, AgentLink shows it in the **Approvals** tab. Approve or deny from the phone; the bridge continues only after the decision is received.

## Troubleshooting

### Android cannot connect after scanning

- Make sure the bridge process is still running.
- For Dev Tunnels, re-run the bridge command if the connect token expired.
- For Tailscale, make sure Android and the developer machine are in the same tailnet.
- Check that the Android machine card uses the expected endpoint.

### Dev Tunnel login or tunnel creation fails

Run:

```powershell
.\bridge\.tools\devtunnel.exe user login -d
```

or, if `devtunnel` is on PATH:

```powershell
devtunnel user login -d
```

Then retry:

```powershell
python .\bridge\run.py start --transport devtunnel
```

### Copilot agent is not available

Install and sign in to GitHub Copilot CLI on the developer machine, then confirm this works:

```powershell
copilot --acp
```

## For contributors

This README is for users. Development and agent instructions live in:

- `CLAUDE.md`
- `docs/README.md`
- `docs/architecture.md`
- `docs/acp-bridge-contract.md`
- `docs/android-app.md`
- `docs/security-model.md`

