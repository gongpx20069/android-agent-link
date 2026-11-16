param([string]$Artifacts = "")
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$profile = Join-Path ([IO.Path]::GetTempPath()) ("agentlink-web-test-" + [Guid]::NewGuid().ToString('N'))
$socket = [Net.WebSockets.ClientWebSocket]::new()
$script:sequence = 0
$script:browserErrors = @()
$script:dragData = $null
$fixture = $null
$renderer = $null

function Invoke-Cdp([string]$Method, [hashtable]$Parameters = @{}) {
    $id = ++$script:sequence
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{id=$id;method=$Method;params=$Parameters} | ConvertTo-Json -Depth 40 -Compress))
    $timeout = [Threading.CancellationTokenSource]::new(20000)
    try {
        $null = $socket.SendAsync([ArraySegment[byte]]::new($bytes), [Net.WebSockets.WebSocketMessageType]::Text, $true, $timeout.Token).GetAwaiter().GetResult()
        while ($true) {
            $stream = [IO.MemoryStream]::new()
            try {
                $buffer = [byte[]]::new(65536)
                do {
                    $part = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $timeout.Token).GetAwaiter().GetResult()
                    if ($part.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { throw 'Browser disconnected' }
                    $stream.Write($buffer, 0, $part.Count)
                } while (-not $part.EndOfMessage)
                $reply = [Text.Encoding]::UTF8.GetString($stream.ToArray()) | ConvertFrom-Json -Depth 50
            } finally { $stream.Dispose() }
            if ($reply.method -eq 'Runtime.exceptionThrown') { $script:browserErrors += $reply.params }
            if ($reply.method -eq 'Input.dragIntercepted') { $script:dragData = $reply.params.data }
            if ($reply.id -eq $id) {
                if ($reply.error) { throw ($reply.error | ConvertTo-Json -Compress) }
                return $reply.result
            }
        }
    } finally { $timeout.Dispose() }
}
function Eval([string]$Expression) {
    $result = Invoke-Cdp 'Runtime.evaluate' @{expression=$Expression;returnByValue=$true;awaitPromise=$true}
    if ($result.exceptionDetails) { throw ($result.exceptionDetails | ConvertTo-Json -Depth 15 -Compress) }
    return $result.result.value
}
function Wait-Js([string]$Expression) {
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    while (-not (Eval $Expression)) {
        if ($script:browserErrors.Count) { throw ($script:browserErrors | ConvertTo-Json -Depth 15 -Compress) }
        if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out: $Expression`n$(Eval 'document.body.innerText.slice(-3000)')" }
        Start-Sleep -Milliseconds 100
    }
}
function Fixture-Command([string]$Command) {
    $fixture.StandardInput.WriteLine($Command)
    $task = $fixture.StandardOutput.ReadLineAsync()
    if (-not $task.Wait(10000)) { throw "Fixture timed out: $Command" }
    if (($task.Result | ConvertFrom-Json).ok -ne $Command) { throw "Fixture failed: $Command" }
}
function Screenshot([string]$Name) {
    if (-not $Artifacts) { return }
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($Artifacts))
    $result = Invoke-Cdp 'Page.captureScreenshot' @{format='png';captureBeyondViewport=$false}
    [IO.File]::WriteAllBytes((Join-Path $Artifacts $Name), [Convert]::FromBase64String($result.data))
}
try {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path $root '.venv\Scripts\python.exe'
    $info.ArgumentList.Add((Join-Path $PSScriptRoot 'web_browser_fixture.py'))
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.UseShellExecute = $false
    $fixture = [Diagnostics.Process]::Start($info)
    $fixtureErrors = $fixture.StandardError.ReadToEndAsync()
    $readyTask = $fixture.StandardOutput.ReadLineAsync()
    if (-not $readyTask.Wait(15000)) { throw 'Fixture did not start' }
    if (-not $readyTask.Result) { throw $fixtureErrors.GetAwaiter().GetResult() }
    $ready = $readyTask.Result | ConvertFrom-Json
    $renderer = Start-Process -FilePath 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' -PassThru -ArgumentList @(
        '--headless=new','--disable-gpu','--disable-extensions','--disable-background-networking','--no-first-run',
        '--no-default-browser-check','--remote-debugging-port=0','--remote-debugging-address=127.0.0.1',
        "--user-data-dir=$profile",'about:blank')
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    while (-not (Test-Path -LiteralPath "$profile\DevToolsActivePort")) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Dedicated test browser did not start' }
        Start-Sleep -Milliseconds 100
    }
    $port = (Get-Content -LiteralPath "$profile\DevToolsActivePort")[0]
    $targets = Invoke-RestMethod "http://127.0.0.1:$port/json/list"
    $page = $targets | Where-Object type -eq 'page' | Select-Object -First 1
    if (-not $page) { throw 'Dedicated browser has no page target' }
    $null = $socket.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $null = Invoke-Cdp 'Page.enable'
    $null = Invoke-Cdp 'Runtime.enable'
    $null = Invoke-Cdp 'Emulation.setDeviceMetricsOverride' @{width=1440;height=960;deviceScaleFactor=1;mobile=$false}
    $null = Invoke-Cdp 'Page.navigate' @{url=$ready.url}
    Wait-Js 'document.querySelector("#login-dialog")?.open'
    $null = Eval @"
document.getElementById("access-code").value=$($ready.code | ConvertTo-Json -Compress);
document.getElementById("login-form").requestSubmit();
"@
    Wait-Js 'document.querySelectorAll("#chat-list .chat").length === 2'
    $null = Eval @'
window.check=(value,message)=>{if(!value)throw new Error(message)};
window.byId=id=>document.getElementById(id);
window.rows=()=>[...document.querySelectorAll("#chat-list .chat")];
window.choose=(id,value)=>{byId(id).value=value;byId(id).dispatchEvent(new Event("change",{bubbles:true}))};
window.chat=(machine,id="same-id")=>rows().find(node=>JSON.parse(node.dataset.key)[0]===machine && JSON.parse(node.dataset.key)[1]===id);
window.type=(id,text)=>{byId(id).value=text;byId(id).dispatchEvent(new Event("input",{bubbles:true}))};
check(!document.querySelector("#chat-list img"),"Chat titles render as text, not HTML");
check(new Set([...document.querySelectorAll("[id]")].map(node=>node.id)).size===document.querySelectorAll("[id]").length,"Unique element IDs");
byId("machines-button").click();
'@
    $null = Eval @"
type("pair-link",$($ready.link | ConvertTo-Json -Compress)); window.confirm=()=>true; byId("pair-form").requestSubmit();
"@
    Wait-Js 'document.querySelectorAll("#chat-list .chat").length === 4'
    $null = Eval @'
window.vm=[...byId("machine-filter").options].find(option=>option.value!=="all"&&option.value!=="local").value;
check(!byId("pair-link").value,"Pairing secret cleared after saving");
byId("machines-dialog").close(); chat("local").click();
'@
    Wait-Js '!document.getElementById("composer").disabled'
    $null = Eval 'type("composer","local draft");chat(vm).click()'
    Wait-Js '!document.getElementById("composer").disabled && document.getElementById("chat-subtitle").textContent.startsWith("VM")'
    $null = Eval 'check(!byId("composer").value,"Draft isolation");type("composer","remote draft");chat("local").click();check(byId("composer").value==="local draft","Local draft preserved");window.before=rows().map(node=>node.dataset.key)'
    $points = Eval @'
(()=>{const s=rows()[2].getBoundingClientRect(),t=rows()[0].getBoundingClientRect();return {sx:s.right-17,sy:s.top+16,tx:t.left+25,ty:t.top+5}})()
'@
    $null = Invoke-Cdp 'Input.setInterceptDrags' @{enabled=$true}
    $null = Invoke-Cdp 'Input.dispatchMouseEvent' @{type='mouseMoved';x=$points.sx;y=$points.sy}
    $null = Invoke-Cdp 'Input.dispatchMouseEvent' @{type='mousePressed';x=$points.sx;y=$points.sy;button='left';buttons=1;clickCount=1}
    $null = Invoke-Cdp 'Input.dispatchMouseEvent' @{type='mouseMoved';x=($points.sx-15);y=($points.sy-9);button='left';buttons=1}
    $null = Invoke-Cdp 'Input.dispatchMouseEvent' @{type='mouseMoved';x=$points.tx;y=$points.ty;button='left';buttons=1}
    $null = Eval 'true'
    if (-not $script:dragData) { throw 'Native drag did not start' }
    foreach ($kind in @('dragEnter','dragOver','drop')) { $null = Invoke-Cdp 'Input.dispatchDragEvent' @{type=$kind;x=$points.tx;y=$points.ty;data=$script:dragData} }
    $null = Invoke-Cdp 'Input.dispatchMouseEvent' @{type='mouseReleased';x=$points.tx;y=$points.ty;button='left';buttons=0;clickCount=1}
    $null = Invoke-Cdp 'Input.setInterceptDrags' @{enabled=$false}
    $null = Eval @'
check(rows()[0].dataset.key===before[2],"Native drag changes order");
check(document.querySelector(".chat.selected").dataset.key===chat("local").dataset.key,"Dragging preserves selection");
choose("machine-filter",vm);window.globalBefore=JSON.parse(localStorage.getItem("agentlink.web.chat-order.v1"));
rows()[1].dispatchEvent(new KeyboardEvent("keydown",{key:"ArrowUp",altKey:true,bubbles:true}));
choose("machine-filter","all");
check(rows().filter(node=>JSON.parse(node.dataset.key)[0]==="local").every(node=>rows().indexOf(node)===globalBefore.indexOf(node.dataset.key)),"Filtered sorting preserves hidden slots");
chat(vm).click();type("composer","needs approval");byId("send").click();
'@
    Wait-Js 'document.querySelectorAll(".approval .actions button").length === 2'
    $null = Eval 'check(byId("detail-content").textContent.startsWith("VM"),"Approval target is explicit");check(!byId("detail-content").textContent.includes("undefined"),"Approval labels present")'
    Screenshot 'agentlink-web-implementation.png'
    $null = Eval 'document.querySelector(".approval .actions button").click()'
    Wait-Js '!document.querySelector(".approval") && document.getElementById("timeline").textContent.includes("allow-once")'
    $null = Eval 'type("composer","needs question");byId("send").click()'
    Wait-Js '!!document.querySelector(".approval form input")'
    $null = Eval 'document.querySelector(".approval form input[type=text]").value="feature/web";document.querySelector(".approval form").requestSubmit()'
    Wait-Js 'document.getElementById("timeline").textContent.includes("Question answered:")'
    $null = Eval @'
window.realFetch=window.fetch;window.dropNext=true;
window.fetch=async (...args)=>{const response=await realFetch(...args);if(dropNext && args[0]==="/api/control" && JSON.parse(args[1].body).request.action==="chat.send"){dropNext=false;throw new Error("test lost acknowledgement")}return response};
type("composer","lost receipt");byId("send").click();
'@
    Wait-Js 'document.getElementById("error").textContent.includes("test lost acknowledgement")'
    $null = Eval 'check(byId("send").disabled && !byId("pending-notice").hidden,"Uncertain send stays locked");byId("check-send").click()'
    Wait-Js 'document.getElementById("pending-notice").hidden'
    $null = Eval 'check([...document.querySelectorAll(".message.user p")].filter(node=>node.textContent==="lost receipt").length===1,"Lost acknowledgement never resends");window.savedOrder=rows().map(node=>node.dataset.key);window.fetch=realFetch'
    Fixture-Command 'offline'
    Wait-Js 'document.getElementById("composer").disabled && document.getElementById("history-notice").textContent.includes("过期")'
    Screenshot 'agentlink-web-implementation-offline.png'
    $null = Eval 'chat("local").click()'
    Wait-Js '!document.getElementById("composer").disabled'
    $null = Eval 'check(byId("composer").value==="local draft","Offline VM does not affect local drafts");chat(vm).click()'
    Fixture-Command 'online'
    Wait-Js '!document.getElementById("composer").disabled'
    $null = Eval 'check(!byId("history-notice").textContent.includes("状态已过期"),"Recovery clears stale warning")'
    Fixture-Command 'reset'
    Wait-Js 'document.getElementById("timeline").textContent.includes("New generation only")'
    $null = Eval 'check(!byId("timeline").textContent.includes("lost receipt"),"Generation reset removes old stream");check(byId("history-notice").textContent.includes("代际"),"History boundary is explicit")'
    Fixture-Command 'delivery'
    Wait-Js 'document.getElementById("timeline").textContent.includes("已投递（不是任务完成）")'
    $null = Eval 'check(byId("chat-status").textContent==="运行中","Delivery does not finish original work");check(JSON.stringify(rows().map(node=>node.dataset.key))===JSON.stringify(savedOrder),"Status changes preserve manual order");byId("new-chat").click()'
    Wait-Js '!document.getElementById("create-chat").disabled'
    $null = Eval 'type("new-name","Created in browser");byId("new-form").requestSubmit()'
    Wait-Js 'document.getElementById("chat-title").textContent==="Created in browser" && !document.getElementById("new-dialog").open'
    $null = Eval 'check(byId("chat-subtitle").textContent.startsWith("VM"),"New chat uses selected VM");check(document.documentElement.scrollWidth<=1440,"Desktop layout has no horizontal overflow")'
    $savedOrder = Eval 'localStorage.getItem("agentlink.web.chat-order.v1")'
    $null = Invoke-Cdp 'Page.reload'
    Wait-Js 'document.querySelectorAll("#chat-list .chat").length === 5'
    $persisted = Eval 'localStorage.getItem("agentlink.web.chat-order.v1")'
    if ($persisted -ne $savedOrder) { throw 'Manual order did not survive reload' }
    $null = Eval @'
(async()=>{const row=[...document.querySelectorAll("#chat-list .chat")].find(node=>node.querySelector("strong").textContent==="Created in browser");
const [machineId,chatId]=JSON.parse(row.dataset.key);
const response=await fetch("/api/control",{method:"POST",headers:{"Content-Type":"application/json","X-AgentLink-Web":"1"},body:JSON.stringify({machineId,request:{action:"chat.delete",chatId}})});
const data=await response.json();if(data.status!=="ok")throw new Error("Shared deletion failed")})()
'@
    Wait-Js 'document.querySelectorAll("#chat-list .chat").length === 4'
    $null = Eval 'sessionStorage.setItem("agentlink.web.pending.v1","broken")'
    $null = Invoke-Cdp 'Page.reload'
    Wait-Js 'document.querySelectorAll("#chat-list .chat").length === 4'
    $null = Eval 'document.querySelector("#chat-list .chat").click()'
    Wait-Js '!document.getElementById("composer").disabled'
    $null = Eval 'document.getElementById("composer").value="should not send";document.getElementById("composer").dispatchEvent(new Event("input"));if(!document.getElementById("send").disabled)throw new Error("Corrupt pending state must block duplicate sends")'
    if ($script:browserErrors.Count) { throw ($script:browserErrors | ConvertTo-Json -Depth 15 -Compress) }
    Write-Output 'Browser checks passed: pairing, routing, drafts, native drag, filtered ordering, approval/forms, lost receipts, offline/recovery, history reset, delivery semantics, creation, persistence.'
} finally {
    $socket.Dispose()
    if ($fixture -and -not $fixture.HasExited) {
        $fixture.StandardInput.WriteLine('quit')
        if (-not $fixture.WaitForExit(10000)) { Stop-Process -Id $fixture.Id -Force }
    }
    if ($fixture -and $fixture.HasExited -and $fixture.ExitCode -ne 0) { Write-Warning $fixtureErrors.GetAwaiter().GetResult() }
    $processes = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($profile) }
    foreach ($process in $processes) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $profile) {
        Start-Sleep -Milliseconds 400
        Get-Item -LiteralPath $profile | Remove-Item -Recurse -Force
    }
}
