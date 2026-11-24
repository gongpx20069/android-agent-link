/* No provider processes or remote credentials live in this browser. */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const make = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  };
  const keyOf = (machine, chat) => JSON.stringify([machine, chat]);
  const statuses = {idle: "空闲", busy: "运行中", waitingApproval: "等待审批", failed: "失败",
    interrupted: "已中断", unknown: "未确认", offline: "离线 / 状态过期", queued: "已排队",
    running: "运行中", sending: "正在投递", delivered: "已投递（不是任务完成）",
    delivery_unknown: "投递结果未知", background: "后台任务", completed: "已完成",
    cancelled: "已取消", accepted: "已接受", starting: "正在启动", started: "已开始"};
  const label = value => statuses[value] || value || "未确认";
  const machines = new Map(), chats = new Map(), histories = new Map(), drafts = new Map();
  const imageDrafts = new Map(), imageChecks = new Map();
  const toolPages = new Map();
  const toolDone = record => ["completed", "failed", "cancelled"].includes(record.data.status);
  const toolState = record => record.detail ||= {open: false, page: null, loading: false, error: "", controller: null};
  function closeTools() {
    if (!selected) return;
    for (const record of history(selected).records.filter(row => row.kind === "tool")) {
      const detail = toolState(record);
      detail.open = false; detail.controller?.abort(); detail.controller = null; detail.loading = false;
    }
    selectedTool = null;
  }
  const maxImageBytes = 5 * 1024 * 1024, imageTypes = ["image/png", "image/jpeg"];
  let pickerChat = null;
  let selected = null, signedIn = false, catalogBusy = false, reading = false, dragKey = null;
  let detailSignature = "", selectedTool = null, catalogTimer, journalTimer, catalogVersion = 0;
  const busyApprovals = new Set();
  function error(message) { $("error").textContent = message; $("error").hidden = !message; }
  let pendingUnreliable = false;
  function stored(storage, name, fallback, valid) {
    try {
      const raw = window[storage].getItem(name);
      if (raw === null) return fallback;
      const value = JSON.parse(raw);
      if (!valid(value)) throw new Error("数据格式无效");
      return value;
    } catch (failure) {
      if (name.includes("pending")) pendingUnreliable = true;
      error(`无法读取浏览器保存的${name.includes("order") ? "排序" : "发送记录"}：${failure.message}。不会自动重发消息。`);
      return fallback;
    }
  }
  const orderName = "agentlink.web.chat-order.v1", pendingName = "agentlink.web.pending.v1";
  let order = stored("localStorage", orderName, [], value => Array.isArray(value) && value.every(v => typeof v === "string"));
  const pending = new Map(stored("sessionStorage", pendingName, [], value => Array.isArray(value) &&
    value.every(v => Array.isArray(v) && typeof v[0] === "string" && typeof v[1] === "string")));
  function savePending() {
    try { sessionStorage.setItem(pendingName, JSON.stringify([...pending])); }
    catch (failure) { error(`发送记录无法持久保存：${failure.message}。刷新前请核对任务；不要重复发送。`); }
  }
  function loginNeeded() {
    if (signedIn && selected) $("login-error").textContent = "登录已过期，请重新输入本机 Server 的访问码。未确认的发送不会自动重试。";
    closeTools();
    signedIn = false;
    clearTimeout(catalogTimer); clearTimeout(journalTimer);
    renderHeader(); renderDetails();
    if (!$("login-dialog").open) $("login-dialog").showModal();
  }
  async function api(path, body, signal) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    signal?.addEventListener("abort", abort);
    if (signal?.aborted) controller.abort();
    const timer = setTimeout(() => controller.abort(), path.endsWith("/pair") ? 70000 : 30000);
    try {
      const response = await fetch(path, {method: body === undefined ? "GET" : "POST",
        credentials: "same-origin", cache: "no-store", signal: controller.signal,
        headers: body === undefined ? {} : {"Content-Type": body instanceof Blob ? body.type : "application/json", "X-AgentLink-Web": "1"},
        body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body)});
      const result = await response.json();
      if (!response.ok) {
        if (response.status === 401 && path !== "/api/login") loginNeeded();
        throw Object.assign(new Error(result.error || `HTTP ${response.status}`), {status: response.status, code: result.code});
      }
      return result;
    } finally { clearTimeout(timer); signal?.removeEventListener("abort", abort); }
  }
  async function control(machineId, action, args = {}, signal) {
    const result = await api("/api/control", {machineId, request: {action, ...args}}, signal);
    if (result.status !== "ok") {
      const failure = new Error(result.message || "Server 没有确认操作成功");
      failure.code = result.code;
      throw failure;
    }
    return result.data;
  }
  function guarded(fn) {
    return async event => {
      try { await fn(event); }
      catch (failure) { if (failure.status !== 401) error(failure.message); }
    };
  }
  function button(text, action, className) {
    const node = make("button", text, className);
    node.type = "button";
    node.addEventListener("click", guarded(action));
    return node;
  }
  function machineName(id) { return machines.get(id)?.name || "已移除的电脑"; }
  function snapshot(key = selected) { return key ? chats.get(key) : null; }
  function attachmentUrl(chat, id = null, capabilities = false) {
    const query = new URLSearchParams({machineId: chat.machineId, chatId: chat.chatId});
    if (id) query.set("id", id);
    return `/api/attachments${capabilities ? "/capabilities" : ""}?${query}`;
  }
  function removeImage(key) {
    const draft = imageDrafts.get(key);
    if (draft) URL.revokeObjectURL(draft.url);
    imageDrafts.delete(key); imageChecks.delete(key);
  }
  function renderImage() {
    const draft = imageDrafts.get(selected);
    $("image-draft").hidden = !draft;
    if (draft) {
      if ($("image-preview").getAttribute("src") !== draft.url) $("image-preview").src = draft.url;
      $("image-name").textContent = `${draft.file.name || "粘贴的图片"} · ${(draft.file.size / 1024).toFixed(0)} KiB`;
    } else $("image-preview").removeAttribute("src");
    $("remove-image").disabled = pending.has(selected);
  }
  async function attachImage(chat, files) {
    if (!chat || !signedIn || pending.has(chat.key) || !history(chat.key).online)
      throw new Error("请先选择在线聊天，并核实未确认的发送。");
    if (files.length !== 1) throw new Error("每条消息只支持一张图片，请只选择或粘贴一张。");
    const file = files[0];
    if (!imageTypes.includes(file.type) || !file.size || file.size > maxImageBytes)
      throw new Error("请选择不超过 5 MiB 的 PNG/JPEG 图片。");
    if (!imageDrafts.has(chat.key) && !imageChecks.has(chat.key) &&
        new Set([...imageDrafts.keys(), ...imageChecks.keys()]).size >= 8)
      throw new Error("最多保留 8 个聊天的图片草稿，请先发送或移除其他图片。");
    const ticket = Symbol(), version = catalogVersion;
    imageChecks.set(chat.key, ticket); renderHeader();
    const url = URL.createObjectURL(file);
    let retained = false;
    try {
      const image = new Image(); image.src = url;
      await image.decode();
      if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > 16000000)
        throw new Error("图片不能超过 1600 万像素，请先缩小尺寸。");
      const capabilities = await api(attachmentUrl(chat, null, true));
      if (capabilities.version !== 1 || capabilities.imageSupported !== true)
        throw new Error("当前会话尚未声明支持图片。新聊天请先发一条文字建立连接，并确认使用支持图片的模型。");
      if (!signedIn || version !== catalogVersion || imageChecks.get(chat.key) !== ticket || !chats.has(chat.key)) return;
      removeImage(chat.key);
      imageDrafts.set(chat.key, {file, url}); retained = true;
    } catch (failure) {
      if (failure.name === "EncodingError") throw new Error("图片无法解码，请选择有效的 PNG/JPEG。");
      throw failure;
    } finally {
      if (!retained) URL.revokeObjectURL(url);
      if (imageChecks.get(chat.key) === ticket) imageChecks.delete(chat.key);
      renderImage(); renderHeader();
    }
  }
  function imageBlock(chat, metadata) {
    const block = make("div", undefined, "message-image");
    if (!metadata || !/^[0-9a-f]{64}$/.test(metadata.id) || !imageTypes.includes(metadata.mimeType) ||
        !Number.isInteger(metadata.size) || metadata.size <= 0 || metadata.size > maxImageBytes) {
      block.append(make("small", "图片引用无效，无法显示。", "error-text"));
      return block;
    }
    const link = make("a"), image = make("img");
    link.href = attachmentUrl(chat, metadata.id); link.target = "_blank"; link.rel = "noopener";
    image.alt = "消息图片"; image.loading = "lazy"; image.src = link.href;
    image.addEventListener("error", () => {
      block.replaceChildren(make("small", "图片不可用：可能已清理、电脑离线或登录已过期。", "error-text"),
        button("重新加载图片", () => block.replaceWith(imageBlock(chat, metadata))));
    }, {once: true});
    link.append(image); block.append(link, make("small", "点击查看原图 · 右键可复制图片"));
    return block;
  }
  function history(key) {
    if (!histories.has(key) && histories.size >= 8) {
      const oldest = [...histories.keys()].find(value => value !== selected);
      histories.delete(oldest);
    }
    if (!histories.has(key)) histories.set(key, {cursor: 0, generation: null, records: [], tasks: [],
      approvals: [], readAt: 0, online: false, notice: "", version: 0, rendered: -1});
    return histories.get(key);
  }
  function sortedChats() {
    const positions = new Map(order.map((key, index) => [key, index]));
    return [...chats.values()].sort((a, b) => (positions.get(a.key) ?? Infinity) - (positions.get(b.key) ?? Infinity));
  }
  function visibleChats() {
    const machine = $("machine-filter").value, query = $("search").value.trim().toLowerCase();
    return sortedChats().filter(chat => (machine === "all" || chat.machineId === machine) &&
      `${chat.chatTitle || ""} ${chat.workspacePath || ""} ${machineName(chat.machineId)}`.toLowerCase().includes(query));
  }
  function renderList() {
    if (dragKey) return;
    const focusKey = document.activeElement?.dataset.key;
    const scroll = $("chat-list").scrollTop;
    const visible = visibleChats();
    $("chat-count").textContent = visible.length;
    const nodes = visible.slice(0, 1000).map(chat => {
      const state = machines.get(chat.machineId)?.online ? chat.status : "offline";
      const node = button("", () => select(chat.key), `chat${selected === chat.key ? " selected" : ""}`);
      node.dataset.key = chat.key; node.draggable = true;
      node.setAttribute("aria-current", String(selected === chat.key));
      node.append(make("strong", chat.chatTitle || chat.chatId), make("span", "⠿", "grip"),
        make("small", `${machineName(chat.machineId)} · ${chat.agentId || ""}`),
        make("span", label(state), `badge ${statuses[state] ? state : "unknown"}`));
      node.title = `${machineName(chat.machineId)}\n${chat.workspacePath || ""}`;
      node.addEventListener("dragstart", event => {
        dragKey = chat.key; node.classList.add("dragging");
        event.dataTransfer.effectAllowed = "move"; event.dataTransfer.setData("text/plain", chat.key);
      });
      node.addEventListener("dragover", event => {
        if (!dragKey || dragKey === chat.key) return;
        event.preventDefault(); event.dataTransfer.dropEffect = "move";
        node.classList.toggle("before", event.clientY < node.getBoundingClientRect().top + node.offsetHeight / 2);
        node.classList.toggle("after", !node.classList.contains("before"));
      });
      node.addEventListener("dragleave", () => node.classList.remove("before", "after"));
      node.addEventListener("drop", event => {
        event.preventDefault();
        const after = event.clientY >= node.getBoundingClientRect().top + node.offsetHeight / 2;
        const source = dragKey; dragKey = null;
        if (source) reorder(source, chat.key, after);
      });
      node.addEventListener("dragend", () => { dragKey = null; renderList(); });
      node.addEventListener("keydown", event => {
        if (!event.altKey || !["ArrowUp", "ArrowDown"].includes(event.key)) return;
        event.preventDefault();
        const index = visible.findIndex(item => item.key === chat.key);
        const target = visible[index + (event.key === "ArrowUp" ? -1 : 1)];
        if (target) reorder(chat.key, target.key, event.key === "ArrowDown");
      });
      return node;
    });
    if (!nodes.length) nodes.push(make("p", "没有匹配的聊天", "empty"));
    if (visible.length > 1000) nodes.push(make("p", "仅展示前 1000 条，请缩小搜索范围。", "notice"));
    $("chat-list").replaceChildren(...nodes);
    $("chat-list").scrollTop = scroll;
    if (focusKey) [...$("chat-list").children].find(node => node.dataset.key === focusKey)?.focus({preventScroll: true});
  }
  function reorder(source, target, after) {
    const visible = visibleChats().map(chat => chat.key);
    if (source === target || !visible.includes(source) || !visible.includes(target)) return;
    const moved = visible.filter(key => key !== source);
    moved.splice(moved.indexOf(target) + Number(after), 0, source);
    const slots = new Set(visible);
    order = sortedChats().map(chat => slots.has(chat.key) ? moved.shift() : chat.key);
    try { localStorage.setItem(orderName, JSON.stringify(order)); }
    catch (failure) { error(`排序只在本次页面内有效，无法保存：${failure.message}`); }
    renderList();
  }
  async function loadMachines() {
    const result = await api("/api/machines");
    const ids = new Set(result.machines.map(item => item.id));
    for (const id of machines.keys()) if (!ids.has(id)) machines.delete(id);
    for (const item of result.machines) machines.set(item.id, {...machines.get(item.id), ...item});
    for (const [key, chat] of chats) if (!ids.has(chat.machineId)) { chats.delete(key); removeImage(key); }
    const previous = $("machine-filter").value;
    $("machine-filter").replaceChildren(new Option("全部电脑", "all"),
      ...result.machines.map(item => new Option(item.name, item.id)));
    $("machine-filter").value = ids.has(previous) ? previous : "all";
    renderMachines(); renderList();
    if (selected && !chats.has(selected)) select(null);
  }
  async function refreshCatalog() {
    clearTimeout(catalogTimer);
    if (!signedIn || catalogBusy) return;
    catalogBusy = true;
    const remaining = [...machines.values()], version = catalogVersion;
    try {
      await Promise.all(Array.from({length: Math.min(4, remaining.length)}, async () => {
        while (remaining.length && signedIn && version === catalogVersion) {
          const machine = remaining.shift();
          try {
            let offset = 0, more = true;
            const rows = [];
            while (more && rows.length < 5000) {
              const result = await control(machine.id, "chat.list", {offset, limit: 100, includeDeleted: true});
              rows.push(...result.chats); offset = result.nextOffset; more = result.hasMore;
            }
            if (more) throw new Error("聊天数超过 5000，目录未完整载入。");
            if (machines.get(machine.id) !== machine || version !== catalogVersion) continue;
            for (const row of rows) {
              const key = keyOf(machine.id, row.chatId);
              if (row.deleted) {
                chats.delete(key); removeImage(key);
                if (selected === key) renderImage();
                continue;
              }
              chats.set(key, {...row, machineId: machine.id, key});
            }
            machine.online = true; machine.error = "";
          } catch (failure) {
            machine.online = false; machine.error = failure.message;
          }
        }
      }));
    } finally {
      catalogBusy = false;
      if (signedIn) {
        renderList(); renderHeader(); renderMachines();
        catalogTimer = setTimeout(refreshCatalog, document.hidden ? 30000 : 10000);
      }
    }
  }
  function select(key) {
    if (selected) drafts.set(selected, $("composer").value);
    closeTools();
    selected = key; selectedTool = null; detailSignature = "";
    $("composer").value = drafts.get(key) || "";
    renderImage();
    $("timeline").replaceChildren();
    $("new-messages").hidden = true;
    if (key) { history(key).rendered = -1; renderTimeline(); }
    else $("timeline").append(make("p", "选择聊天，或创建一个新会话。", "empty"));
    renderList(); renderHeader(); renderDetails();
    clearTimeout(journalTimer); pollJournal();
  }
  function renderHeader() {
    const chat = snapshot(), state = chat ? history(chat.key) : null;
    const online = signedIn && chat && machines.get(chat.machineId)?.online && state?.online && Date.now() - state.readAt < 15000;
    $("chat-title").textContent = chat?.chatTitle || (chat ? chat.chatId : "选择一个聊天");
    $("chat-subtitle").textContent = chat ? `${machineName(chat.machineId)} · ${chat.workspacePath || chat.workspaceId}` : "";
    const status = chat ? (online ? chat.status : "offline") : "unknown";
    $("chat-status").textContent = chat ? label(status) : "";
    $("chat-status").className = `badge ${statuses[status] ? status : "unknown"}`;
    $("composer").disabled = !online;
    $("send").disabled = !online || pendingUnreliable || pending.has(selected) || imageChecks.has(selected) ||
      (!$("composer").value.trim() && !imageDrafts.has(selected));
    $("attach-image").disabled = !online || pending.has(selected) || imageChecks.has(selected);
    $("attach-image").textContent = imageChecks.has(selected) ? "检查图片…" : "＋ 图片";
    $("remove-image").disabled = pending.has(selected);
    $("rename").disabled = !online;
    $("composer-target").textContent = chat ? `发送到 ${machineName(chat.machineId)} · ${chat.chatTitle || chat.chatId}` : "仅连接本机 Web 入口";
    $("history-notice").textContent = [state?.notice, state?.connectionError].filter(Boolean).join("\n");
    $("history-notice").hidden = !$("history-notice").textContent;
    $("pending-notice").hidden = !pendingUnreliable && !pending.has(selected);
    $("pending-text").textContent = pendingUnreliable ? "浏览器发送记录不可读，无法判断之前的发送结果。请先在目标电脑核对任务再解除发送锁。" :
      pending.has(selected) ? `发送尚未核实（${pending.get(selected)}）。为避免重复执行，已暂停再次发送。` : "";
    $("cancel-task").disabled = !online || !state?.tasks.some(task => ["running", "accepted", "starting", "waitingApproval"].includes(task.state));
    $("queue-list").replaceChildren(...(state?.tasks || []).filter(task =>
      ["queued", "sending", "background", "delivery_unknown"].includes(task.state)).map(task => {
        const row = make("div", `${label(task.state)} · ${task.taskId}`);
        if (task.state === "queued") {
          const remove = button("移出队列", () => cancel(chat, task.taskId));
          remove.disabled = !online; row.append(remove);
        }
        return row;
      }));
  }
  function inlineText(node, text, depth = 0) {
    if (depth >= 6) { node.append(document.createTextNode(text)); return; }
    const pattern = /\\([\\`*_[\]{}()#+.!|>~-])|(`+)([\s\S]*?)\2(?!`)|\*\*([^*\n]+)\*\*|__([^_\n]+)__|~~([^~\n]+)~~|\*([^*\n]+)\*|!\[([^\]\n]*)\]\(([^)\n]*)\)|\[([^\]\n]+)\]\(([^)\n]+)\)/g;
    let end = 0;
    for (const match of text.matchAll(pattern)) {
      node.append(document.createTextNode(text.slice(end, match.index)));
      if (match[1]) node.append(document.createTextNode(match[1]));
      else if (match[2]) node.append(make("code", match[3]));
      else if (match[8] !== undefined) node.append(document.createTextNode(match[0]));
      else if (match[10]) {
        let url;
        try { url = new URL(match[11]); } catch { /* Invalid links stay visible as text. */ }
        if (url && ["https:", "http:"].includes(url.protocol) && !url.username && !url.password) {
          const link = make("a"); link.href = url.href; link.target = "_blank"; link.rel = "noopener noreferrer";
          inlineText(link, match[10], depth + 1); node.append(link);
        } else node.append(document.createTextNode(match[0]));
      } else {
        const element = make(match[4] || match[5] ? "strong" : match[6] ? "del" : "em");
        inlineText(element, match[4] || match[5] || match[6] || match[7], depth + 1);
        node.append(element);
      }
      end = match.index + match[0].length;
    }
    node.append(document.createTextNode(text.slice(end)));
  }
  function tableCells(line) {
    let text = line.trim();
    if (text.startsWith("|")) text = text.slice(1);
    if (text.endsWith("|") && !text.endsWith("\\|")) text = text.slice(0, -1);
    const cells = [];
    let cell = "", ticks = 0;
    for (let index = 0; index < text.length; index++) {
      const char = text[index];
      if (char === "\\" && index + 1 < text.length) { cell += char + text[++index]; continue; }
      if (char === "`") {
        let length = 1;
        while (text[index + length] === "`") length++;
        if (!ticks) ticks = length;
        else if (ticks === length) ticks = 0;
        cell += "`".repeat(length); index += length - 1;
      } else if (char === "|" && !ticks) { cells.push(cell.trim()); cell = ""; }
      else cell += char;
    }
    cells.push(cell.trim());
    return cells;
  }
  function markdown(text, depth = 0) {
    const box = make("div");
    const lines = String(text).replace(/\r\n?/g, "\n").split("\n");
    for (let index = 0; index < lines.length; index++) {
      const line = lines[index], fence = line.match(/^\s{0,3}(`{3,}|~{3,})(.*)$/);
      if (fence) {
        const code = [];
        while (++index < lines.length) {
          const close = lines[index].trim();
          if (close.length >= fence[1].length && [...close].every(char => char === fence[1][0])) break;
          code.push(lines[index]);
        }
        const pre = make("pre"); pre.append(make("code", code.join("\n"))); box.append(pre); continue;
      }
      const headings = tableCells(line), separators = index + 1 < lines.length ? tableCells(lines[index + 1]) : [];
      if (line.includes("|") && headings.length === separators.length && separators.every(cell => /^:?-{3,}:?$/.test(cell))) {
        const wrapper = make("div", undefined, "markdown-table"), table = make("table"), head = make("thead"), body = make("tbody");
        const addRow = (values, tag) => {
          const row = make("tr");
          headings.forEach((_, column) => {
            const alignment = separators[column];
            const cell = make(tag, undefined, alignment.endsWith(":") ? (alignment.startsWith(":") ? "align-center" : "align-right") : "align-left");
            if (tag === "th") cell.scope = "col";
            inlineText(cell, values[column] || ""); row.append(cell);
          });
          return row;
        };
        head.append(addRow(headings, "th")); index++;
        while (index + 1 < lines.length && lines[index + 1].trim() && lines[index + 1].includes("|"))
          body.append(addRow(tableCells(lines[++index]), "td"));
        table.append(head, body); wrapper.append(table); box.append(wrapper); continue;
      }
      if (/^\s{0,3}>/.test(line) && depth < 6) {
        const quoted = [line.replace(/^\s{0,3}> ?/, "")];
        while (index + 1 < lines.length && /^\s{0,3}>/.test(lines[index + 1]))
          quoted.push(lines[++index].replace(/^\s{0,3}> ?/, ""));
        const quote = make("blockquote"); quote.append(markdown(quoted.join("\n"), depth + 1)); box.append(quote); continue;
      }
      if (/^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { box.append(make("hr")); continue; }
      const item = line.match(/^\s*(?:([-+*])|(\d+)[.)])\s+(.*)$/);
      if (item) {
        const tag = item[1] ? "ul" : "ol";
        const list = make(tag);
        if (tag === "ol") list.start = Number(item[2]);
        let current = item;
        do {
          const entry = make("li"); inlineText(entry, current[3]); list.append(entry);
          const next = lines[index + 1]?.match(/^\s*(?:([-+*])|(\d+)[.)])\s+(.*)$/);
          if (!next || Boolean(next[1]) !== Boolean(item[1])) break;
          current = next; index++;
        } while (true);
        box.append(list); continue;
      }
      const heading = line.match(/^\s{0,3}(#{1,6})\s+(.*)$/);
      const node = make(heading ? `h${heading[1].length}` : "div", undefined, "line");
      inlineText(node, heading ? heading[2].replace(/\s+#+\s*$/, "") : line); box.append(node);
    }
    return box;
  }
  function fold(state, event, chat) {
    if (event.truncated) state.notice = event.notice || "某条 Server 事件过长，详情不完整。";
    if (event.type === "operation.accepted") {
      if (!state.records.some(row => row.kind === "user" && row.id === event.operationId))
        state.records.push({kind: "user", id: event.operationId, source: event.source,
          image: event.image, text: event.content || (event.truncated ? "[该输入超出共享日志限制]" : ""), timestamp: event.timestamp});
    } else if (event.type === "session/update") {
      const update = event.update || {};
      if (["agent_message_chunk", "user_message_chunk"].includes(update.sessionUpdate)) {
        const text = typeof update.content === "string" ? update.content : update.content?.text;
        if (typeof text === "string") {
          const kind = update.sessionUpdate === "agent_message_chunk" ? "assistant" : "user";
          const last = state.records.at(-1);
          if (last?.kind === kind && last.stream && !update.image && !last.image && last.operationId === event.operationId) last.text += text;
          else state.records.push({kind, text, image: update.image, stream: true, operationId: event.operationId, timestamp: event.timestamp});
        }
      } else if (["tool_call", "tool_call_update"].includes(update.sessionUpdate)) {
        let record = state.records.findLast(row => row.kind === "tool" && row.id === update.toolCallId);
        if (!record || update.sessionUpdate === "tool_call") {
          record = {kind: "tool", id: update.toolCallId, data: {}, timestamp: event.timestamp};
          state.records.push(record);
        }
        const wasDone = toolDone(record);
        record.data = {...record.data, ...update};
        if (!wasDone && toolDone(record) && toolState(record).open) loadTool(record, chat, true);
      }
    } else if (event.type === "approval.resolved") {
      state.records.push({kind: "notice", text: `审批结果：${event.status}${event.optionId ? " · " + event.optionId : ""}`});
    } else if (event.type === "operation.done" && (event.error || event.status === "failed")) {
      state.records.push({kind: "notice", text: event.error || "任务失败，请查看工具详情。"});
    }
    if (state.records.length > 1000) {
      for (const removed of state.records.splice(0, state.records.length - 1000)) {
        if (removed.detail) {
          removed.detail.open = false; removed.detail.controller?.abort(); removed.detail.controller = null;
        }
        if (selectedTool === removed) selectedTool = null;
      }
      state.notice = "页面只保留最近 1000 个消息/工具块；更早记录可能仍在 Server 日志中。";
    }
    const tail = state.records.at(-1);
    if (tail?.text?.length > 200000) {
      tail.text = tail.text.slice(-200000);
      state.notice = "过长的单条内容已保留末尾 200000 字符；详情请查看 Server 日志。";
    }
    state.version++;
  }
  async function loadTool(record, chat, refresh = false, next = false) {
    const detail = toolState(record);
    if (!detail.open || !chat) return;
    if (detail.loading && !refresh) return;
    detail.controller?.abort();
    const controller = new AbortController();
    detail.controller = controller;
    const previous = toolPages.get(detail.page);
    const cursor = next ? previous?.next : 0;
    const cacheKey = JSON.stringify([chat.key, record.data.detailRef, record.data.detailVersion, cursor]);
    if (!refresh && toolPages.has(cacheKey)) {
      detail.page = cacheKey; detail.loading = false; detail.error = "";
      history(chat.key).version++; renderTimeline(); return;
    }
    detail.loading = true; detail.error = "";
    history(chat.key).version++; renderTimeline();
    try {
      if (!record.data.detailRef) throw new Error("该工具没有可用的详情引用，请更新 Server 或重新加载历史。");
      if (next && (!previous || cursor == null)) throw new Error("快照缓存已清理，请刷新。");
      const page = await control(chat.machineId, "tool.read", {chatId: chat.chatId, ref: record.data.detailRef,
        ...(next ? {snapshot: previous.snapshot, cursor} : {})}, controller.signal);
      if (controller.signal.aborted || selected !== chat.key || !detail.open) return;
      if (page.ref !== record.data.detailRef || page.offset !== cursor || typeof page.text !== "string" ||
          page.text.length > 8192 || !Number.isInteger(page.version) || page.version < 1 ||
          !/^[a-f0-9]{48}$/.test(page.snapshot) || (next && page.snapshot !== previous.snapshot))
        throw new Error("Server 返回的工具详情快照无效。");
      const key = JSON.stringify([chat.key, page.ref, page.version, page.offset]);
      toolPages.delete(key); toolPages.set(key, page);
      while (toolPages.size > 128) toolPages.delete(toolPages.keys().next().value);
      detail.page = key;
    } catch (failure) {
      if (!controller.signal.aborted) detail.error = failure.message;
    } finally {
      if (detail.controller === controller) {
        detail.loading = false; detail.controller = null;
        history(chat.key).version++;
        if (selected === chat.key) { renderTimeline(); detailSignature = ""; renderDetails(); }
      }
    }
  }
  function toolBody(record, chat) {
    const detail = toolState(record), body = make("div"), page = toolPages.get(detail.page);
    if (!toolDone(record)) body.append(make("p", "运行中 · 当前为快照，可手动刷新。", "muted"));
    if (detail.loading) body.append(make("p", "正在加载详情…"));
    if (detail.error) body.append(make("p", detail.error, "error-text"));
    if (page) body.append(make("pre", page.text, "detail-code"));
    else if (detail.page) body.append(make("p", "快照缓存已清理，请刷新。", "muted"));
    const refresh = button("刷新详情", () => loadTool(record, chat, true));
    refresh.disabled = detail.loading;
    body.append(refresh);
    if (page?.next != null) {
      const more = button("下一页", () => loadTool(record, chat, false, true));
      more.disabled = detail.loading; body.append(more);
    }
    return body;
  }
  function renderTimeline() {
    if (!selected) return;
    const state = history(selected);
    if (state.rendered === state.version) return;
    const timeline = $("timeline"), pinned = timeline.scrollHeight - timeline.scrollTop - timeline.clientHeight < 90;
    const oldScroll = timeline.scrollTop;
    const nodes = state.records.map((record, index) => {
      if (record.kind === "tool") {
        const detail = toolState(record), chat = snapshot();
        const node = make("details", undefined, "tool");
        node.dataset.record = String(index); node.open = detail.open;
        node.append(make("summary", `${record.data.title || record.data.toolCallId || "工具"} · ${label(record.data.status)}`),
          toolBody(record, chat),
          button("在右侧查看", () => { selectedTool = record; detailSignature = ""; openDetails(); renderDetails(); }));
        node.addEventListener("toggle", () => {
          if (!node.isConnected || node.open === detail.open) return;
          detail.open = node.open;
          if (detail.open) loadTool(record, chat);
          else {
            detail.controller?.abort(); detail.controller = null; detail.loading = false;
            if (selectedTool === record) { selectedTool = null; detailSignature = ""; renderDetails(); }
          }
        });
        return node;
      }
      if (record.kind === "notice") return make("div", record.text, "notice");
      const node = make("article", undefined, `message ${record.kind}`);
      node.append(make("header", `${record.kind === "user" ? (record.source === "mochi" ? "自动输入" : "已接收输入") : "助手"}${record.timestamp ? " · " + new Date(record.timestamp).toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"}) : ""}`),
        record.kind === "assistant" ? markdown(record.text) : make("p", record.text));
      if (record.image) node.append(imageBlock(snapshot(), record.image));
      const task = state.tasks.find(item => item.taskId === record.id);
      if (task) node.append(make("div", label(task.state), "receipt"));
      if (record.text) node.append(button("复制", () => navigator.clipboard.writeText(record.text), "message-copy"));
      return node;
    });
    timeline.replaceChildren(...(nodes.length ? nodes : [make("p", "这里会展示 Server 保留的共享记录。发送消息开始工作。", "empty")]));
    if (pinned || state.rendered < 0) timeline.scrollTop = timeline.scrollHeight;
    else { timeline.scrollTop = oldScroll; $("new-messages").hidden = false; }
    state.rendered = state.version;
  }
  async function pollJournal() {
    clearTimeout(journalTimer);
    if (!signedIn || reading || !selected || document.hidden) {
      if (signedIn) journalTimer = setTimeout(pollJournal, 1500);
      return;
    }
    const chat = snapshot();
    if (!chat) { select(null); return; }
    reading = true;
    const state = history(chat.key);
    let more = false;
    try {
      const result = await control(chat.machineId, "chat.read", {chatId: chat.chatId, afterEventId: state.cursor, limit: 100});
      if (!signedIn || !machines.has(chat.machineId)) return;
      if (state.generation && state.generation !== result.eventGeneration) {
        if (selected === chat.key) closeTools();
        state.cursor = 0; state.records = []; state.version++;
        state.generation = result.eventGeneration;
        state.notice = "Server 事件代际已变化，正在重新载入保留记录；不会自动重发任务。";
        more = true;
      } else {
        state.generation = result.eventGeneration;
        if (result.truncated && !state.notice.includes("仅保留部分共享历史"))
          state.notice = [state.notice, "Server 仅保留部分共享历史，较早记录不在此页面中。"].filter(Boolean).join("\n");
        for (const event of result.events) if (event.eventId > state.cursor) fold(state, event, chat);
        state.cursor = result.nextEventId; more = result.hasMore;
      }
      if (JSON.stringify(state.tasks) !== JSON.stringify(result.tasks)) state.version++;
      state.tasks = result.tasks; state.approvals = result.approvals;
      state.online = true; state.readAt = Date.now();
      state.connectionError = "";
      machines.get(chat.machineId).online = true;
      machines.get(chat.machineId).error = "";
      let characters = state.records.reduce((total, record) => total + (record.text?.length || JSON.stringify(record.data || {}).length), 0);
      while (characters > 2000000 && state.records.length > 1) {
        const removed = state.records.shift();
        characters -= removed.text?.length || JSON.stringify(removed.data || {}).length;
        state.notice = "较早内容已从页面缓存移出（每个聊天约 200 万字符上限）；Server 日志不受影响。";
      }
      chats.set(chat.key, {...chat, ...result.chat});
      if (selected === chat.key) { renderTimeline(); renderHeader(); renderDetails(); renderList(); }
    } catch (failure) {
      state.online = false;
      state.connectionError = `状态已过期，暂不能在此电脑发送或审批：${failure.message}`;
      if (selected === chat.key) { renderHeader(); detailSignature = ""; renderDetails(); }
    } finally {
      reading = false;
      if (signedIn) journalTimer = setTimeout(pollJournal, selected !== chat.key || more ? 0 : 1200);
    }
  }
  function openDetails() { $("app").classList.remove("details-closed"); $("app").classList.add("details-open"); }
  function renderDetails() {
    const chat = snapshot(), state = chat ? history(chat.key) : null;
    const online = signedIn && state?.online && machines.get(chat?.machineId)?.online && Date.now() - state.readAt < 15000;
    const signature = JSON.stringify([selected, state?.approvals, online, selectedTool?.data, [...busyApprovals]]);
    if (signature === detailSignature) return;
    detailSignature = signature;
    const target = $("detail-content");
    target.replaceChildren(make("p", chat ? `${machineName(chat.machineId)} · ${chat.chatTitle || chat.chatId}` : "选择一个聊天", "muted"));
    for (const approval of state?.approvals || []) {
      const card = make("section", undefined, "approval");
      card.append(make("span", "需要你确认", "badge waitingApproval"), make("h3", approval.summary),
        make("small", `截至 ${new Date(approval.expiresAt).toLocaleTimeString()}`),
        make("pre", JSON.stringify(approval.details, null, 2), "detail-code"));
      const disabled = !online || Date.now() >= approval.expiresAt || busyApprovals.has(approval.approvalId);
      if (approval.interaction === "question") {
        const form = make("form"), fields = [];
        const schema = approval.requestedSchema || approval.details?.requestedSchema || {};
        const properties = Object.entries(schema.properties || {});
        const supported = properties.every(([, spec]) => ["string", "number", "integer", "boolean"].includes(spec.type) && !spec.oneOf);
        let raw;
        if (!supported) {
          form.append(make("label", "此问题含复杂字段，请按上面的 Schema 输入 JSON 答案；Server 会校验。"));
          raw = make("textarea"); raw.required = true; raw.value = "{}"; form.append(raw);
        } else {
          for (const [name, spec] of properties) {
            const field = make("label", spec.title || name);
            let input;
            if (spec.enum) {
              input = make("select"); input.append(new Option("请选择", ""));
              for (const value of spec.enum) input.append(new Option(String(value), String(value)));
            } else {
              input = make("input"); input.type = spec.type === "boolean" ? "checkbox" : spec.type === "string" ? "text" : "number";
              if (spec.type === "integer") input.step = "1";
              if (spec.type === "number") input.step = "any";
              if (spec.minimum !== undefined) input.min = spec.minimum;
              if (spec.maximum !== undefined) input.max = spec.maximum;
              if (spec.minLength !== undefined) input.minLength = spec.minLength;
              if (spec.maxLength !== undefined) input.maxLength = spec.maxLength;
            }
            input.required = spec.type !== "boolean" && (schema.required || []).includes(name);
            input.disabled = disabled;
            field.append(input); form.append(field); fields.push({name, spec, input});
          }
        }
        const submit = make("button", "提交答案", "primary"); submit.type = "submit"; submit.disabled = disabled;
        form.append(submit, button("拒绝", () => decide(chat, approval, {decision: "denied"})));
        form.lastChild.disabled = disabled;
        form.addEventListener("submit", guarded(async event => {
          event.preventDefault();
          const answers = raw ? JSON.parse(raw.value) : Object.fromEntries(fields.filter(({spec, input}) =>
            spec.type === "boolean" || input.value !== "").map(({name, spec, input}) => [name,
            spec.type === "boolean" ? (spec.enum ? input.value === "true" : input.checked) : ["number", "integer"].includes(spec.type) ? Number(input.value) : input.value]));
          await decide(chat, approval, {decision: "approved", answers});
        }));
        card.append(form);
      } else {
        const actions = make("div", undefined, "actions");
        for (const option of approval.options || []) {
          const allow = ["allow_once", "allow_always"].includes(option.kind);
          const valid = allow || ["reject_once", "reject_always"].includes(option.kind);
          const node = button(option.name || option.optionId, () => decide(chat, approval,
            {decision: allow ? "approved" : "denied", optionId: option.optionId}), allow ? "primary" : "");
          node.disabled = disabled || !valid; actions.append(node);
        }
        if (!actions.children.length) actions.append(make("p", "缺少可用的审批选项，请在原生客户端处理。", "error-text"));
        card.append(actions);
      }
      target.append(card);
    }
    if (!state?.approvals.length) target.append(make("p", online ? "没有待审批事项" : "状态尚未确认；缓存不能作为审批依据。", "muted"));
    if (selectedTool) target.append(make("h3", selectedTool.data.title || "工具详情"),
      toolBody(selectedTool, chat));
  }
  async function decide(chat, approval, decision) {
    if (Date.now() >= approval.expiresAt) throw new Error("该审批已过期，请刷新。");
    busyApprovals.add(approval.approvalId); renderDetails();
    try {
      const result = await control(chat.machineId, "approval.decide", {chatId: chat.chatId, approvalId: approval.approvalId, ...decision});
      if (result.status !== decision.decision || (decision.optionId && result.optionId !== decision.optionId))
        error(`审批已由其他操作处理或已过期。实际结果：${result.status} · ${result.optionId || ""}；本次选择没有覆盖它。`);
      history(chat.key).approvals = history(chat.key).approvals.filter(item => item.approvalId !== approval.approvalId);
    } finally { busyApprovals.delete(approval.approvalId); detailSignature = ""; renderDetails(); pollJournal(); }
  }
  async function send() {
    const chat = snapshot(), content = $("composer").value.trim();
    const draft = imageDrafts.get(selected), version = catalogVersion;
    if (!chat || (!content && !draft) || pending.has(chat.key) || $("send").disabled) return;
    if (content.length > 64000) throw new Error("消息不能超过 64000 字符。");
    const operationId = `web_${crypto.randomUUID()}`;
    pending.set(chat.key, operationId); savePending(); renderHeader();
    let submitted = false;
    try {
      const image = draft ? await api(attachmentUrl(chat), draft.file) : null;
      if (!signedIn || version !== catalogVersion) throw new Error("登录状态已变化，图片未发送给助手，请重新登录后检查。");
      submitted = true;
      await control(chat.machineId, "chat.send", {chatId: chat.chatId, content, operationId, ...(image ? {image} : {})});
      pending.delete(chat.key); savePending();
      if ((drafts.get(chat.key) || "").trim() === content) drafts.delete(chat.key);
      if (selected === chat.key && $("composer").value.trim() === content) $("composer").value = "";
      if (imageDrafts.get(chat.key) === draft) removeImage(chat.key);
      error("");
    } catch (failure) {
      if (!submitted || ["INVALID_ARGS", "NOT_FOUND", "PERMISSION_DENIED"].includes(failure.code)) {
        pending.delete(chat.key); savePending();
      }
      if (failure.status === 401) throw failure;
      throw new Error(`发送到 ${machineName(chat.machineId)}：${failure.message}。${submitted ? "结果未确认时请先检查，不要重复发送。" : "尚未提交消息；图片草稿已保留。"}`);
    } finally { renderImage(); renderHeader(); pollJournal(); }
  }
  async function cancel(chat, operationId) {
    if (!confirm(`向 ${machineName(chat.machineId)} 请求停止此任务？已经执行的操作无法撤销。`)) return;
    const result = await control(chat.machineId, "task.cancel", {chatId: chat.chatId, operationId});
    if (result.state !== "cancellation_requested" && result.state !== "removed")
      error(`停止请求结果：${result.state}。请核对任务状态。`);
    pollJournal();
  }
  function renderMachines() {
    const nodes = [...machines.values()].map(machine => {
      const row = make("div");
      row.append(make("strong", `${machine.name}${machine.id === "local" ? " · 本机" : ""}`),
        make("div", machine.endpoint, "muted"),
        make("div", machine.online ? "已连接" : machine.error || "等待连接", machine.online ? "muted" : "error-text"));
      if (machine.id !== "local") row.append(button("移除连接", async () => {
        if (!confirm(`从本机移除 ${machine.name}？不会删除 VM 上的聊天，也不会撤销 VM 上的设备授权。`)) return;
        await api("/api/machines/remove", {id: machine.id}); await loadMachines();
      }));
      return row;
    });
    $("machine-rows").replaceChildren(...nodes);
    const edit = $("edit-machine").value;
    $("edit-machine").replaceChildren(new Option("新增连接", ""), ...[...machines.values()]
      .filter(machine => machine.id !== "local").map(machine => new Option(machine.name, machine.id)));
    if (machines.has(edit)) $("edit-machine").value = edit;
  }
  let newLoad = 0;
  async function newChoices() {
    const serial = ++newLoad, machineId = $("new-machine").value;
    $("create-chat").disabled = true;
    $("new-info").textContent = "正在读取目标电脑的目录和助手…";
    $("new-workspace").replaceChildren(); $("new-agent").replaceChildren();
    $("register-field").hidden = true;
    try {
      const data = await control(machineId, "workspace.list");
      if (serial !== newLoad || machineId !== $("new-machine").value) return;
      $("new-workspace").replaceChildren(...data.workspaces.map(item => new Option(item.displayName || item.absolutePath, item.id)));
      if (data.workspaceCreationEnabled) $("new-workspace").append(new Option("注册已有目录…", "__register__"));
      $("new-agent").replaceChildren(...data.agents.filter(item => item.status === "available")
        .map(item => new Option(item.displayName || item.name || item.id, item.id)));
      $("new-info").textContent = data.workspaces.length ? "聊天将使用所选电脑的目录和运行时。" :
        data.workspaceCreationEnabled ? "此电脑还没有工作目录，请注册已有目录。" : "请先在目标 Server 中打开一个聊天，或使用 --workspace-root 允许注册已有目录。";
      $("create-chat").disabled = !$("new-workspace").options.length || !$("new-agent").options.length;
      if (!$("new-agent").options.length) $("new-info").textContent += " 未发现可用助手，请在目标电脑安装并登录所需 CLI。";
      $("register-field").hidden = $("new-workspace").value !== "__register__";
    } catch (failure) { if (serial === newLoad) $("new-info").textContent = failure.message; }
  }
  $("login-dialog").addEventListener("cancel", event => event.preventDefault());
  $("login-form").addEventListener("submit", async event => {
    event.preventDefault();
    const submit = event.submitter || $("login-form").querySelector("button.primary"); submit.disabled = true;
    try {
      await api("/api/login", {code: $("access-code").value});
      $("access-code").value = ""; $("login-error").textContent = ""; $("login-dialog").close();
      await start();
    } catch (failure) { $("login-error").textContent = failure.message; }
    finally { submit.disabled = false; }
  });
  $("logout").addEventListener("click", guarded(async () => {
    await api("/api/logout", {}); catalogVersion++; signedIn = false;
    closeTools(); toolPages.clear();
    chats.clear(); histories.clear(); drafts.clear(); machines.clear(); selected = null;
    for (const key of imageDrafts.keys()) removeImage(key);
    imageChecks.clear(); pickerChat = null; $("image-file").value = ""; renderImage();
    for (const id of ["access-code", "pair-link", "machine-token", "machine-relay"]) $(id).value = "";
    $("composer").value = ""; renderList(); renderHeader(); renderDetails(); $("timeline").replaceChildren(); loginNeeded();
  }));
  $("composer").addEventListener("input", () => { if (selected) drafts.set(selected, $("composer").value); renderHeader(); });
  $("attach-image").addEventListener("click", () => { pickerChat = snapshot(); $("image-file").click(); });
  $("image-file").addEventListener("change", guarded(async () => {
    const files = [...$("image-file").files], chat = pickerChat;
    $("image-file").value = ""; pickerChat = null;
    if (files.length) await attachImage(chat, files);
  }));
  $("composer").addEventListener("paste", guarded(async event => {
    const files = [...(event.clipboardData?.files || [])];
    if (!files.length) return;
    event.preventDefault();
    await attachImage(snapshot(), files);
  }));
  $("remove-image").addEventListener("click", () => { removeImage(selected); renderImage(); renderHeader(); });
  $("composer").addEventListener("keydown", guarded(async event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) { event.preventDefault(); await send(); }
  }));
  $("send").addEventListener("click", guarded(send));
  $("check-send").addEventListener("click", guarded(async () => {
    const chat = snapshot(), operationId = pending.get(selected);
    if (!chat || !operationId) return;
    const result = await control(chat.machineId, "task.read", {chatId: chat.chatId, taskId: operationId});
    if (!result.task || result.task.state === "unknown") throw new Error("尚未找到该任务。请在目标电脑核对后再手动解除发送锁；不要自动重试。");
    pending.delete(chat.key); savePending();
    error(`已找到发送记录：${label(result.task.state)}。草稿保留，请勿重复发送同一内容。`);
    renderHeader(); pollJournal();
  }));
  $("unlock-send").addEventListener("click", () => {
    if (selected && confirm("仅当你已在目标电脑核对任务后才解除。再次发送可能重复执行。确认解除？")) {
      pending.delete(selected); pendingUnreliable = false; savePending(); renderHeader();
    }
  });
  $("cancel-task").addEventListener("click", guarded(async () => {
    const chat = snapshot(), task = chat && history(chat.key).tasks.find(item => ["running", "accepted", "starting", "waitingApproval"].includes(item.state));
    if (task) await cancel(chat, task.taskId);
  }));
  $("rename").addEventListener("click", guarded(async () => {
    const chat = snapshot(); if (!chat) return;
    const title = prompt(`重命名 ${machineName(chat.machineId)} 上的聊天`, chat.chatTitle || "");
    if (title === null) return;
    const result = await control(chat.machineId, "chat.rename", {chatId: chat.chatId, title, expectedSessionId: chat.sessionId});
    chats.set(chat.key, {...chat, ...result.chat}); renderList(); renderHeader();
  }));
  $("new-chat").addEventListener("click", guarded(async () => {
    $("new-machine").replaceChildren(...[...machines.values()].map(machine => new Option(machine.name, machine.id)));
    $("new-machine").value = snapshot()?.machineId || "local";
    $("new-name").value = ""; $("register-path").value = ""; $("new-dialog").showModal(); await newChoices();
  }));
  $("new-machine").addEventListener("change", newChoices);
  $("new-workspace").addEventListener("change", () => { $("register-field").hidden = $("new-workspace").value !== "__register__"; });
  $("new-form").addEventListener("submit", async event => {
    event.preventDefault();
    const machineId = $("new-machine").value, agentId = $("new-agent").value, title = $("new-name").value;
    let workspaceId = $("new-workspace").value;
    $("create-chat").disabled = true; $("new-machine").disabled = true;
    try {
      if (workspaceId === "__register__") workspaceId = (await control(machineId, "workspace.create",
        {mode: "register_existing", path: $("register-path").value})).workspace.id;
      const data = await control(machineId, "chat.create", {workspaceId, agentId, chatTitle: title, chatId: `chat_${crypto.randomUUID()}`});
      const key = keyOf(machineId, data.chat.chatId);
      chats.set(key, {...data.chat, machineId, key}); $("new-dialog").close(); select(key);
    } catch (failure) { $("new-info").textContent = `创建未确认：${failure.message}。请检查列表后再重试。`; }
    finally { $("create-chat").disabled = false; $("new-machine").disabled = false; refreshCatalog(); }
  });
  $("machines-button").addEventListener("click", () => { renderMachines(); $("machines-dialog").showModal(); });
  $("edit-machine").addEventListener("change", () => {
    const machine = machines.get($("edit-machine").value);
    $("machine-name").value = machine?.name || ""; $("machine-endpoint").value = machine?.endpoint || "";
    $("machine-token").value = ""; $("machine-relay").value = "";
  });
  $("machine-form").addEventListener("submit", async event => {
    event.preventDefault();
    const submit = event.submitter || $("machine-form").querySelector("button.primary"); submit.disabled = true;
    try {
      await api("/api/machines/save", {id: $("edit-machine").value, name: $("machine-name").value,
        endpoint: $("machine-endpoint").value, token: $("machine-token").value,
        headers: $("machine-relay").value ? {"X-Tunnel-Authorization": $("machine-relay").value} : {}});
      $("machine-token").value = ""; $("machine-relay").value = "";
      $("machine-result").textContent = "连接已保存。"; await loadMachines(); await refreshCatalog();
    } catch (failure) { $("machine-result").textContent = failure.message; }
    finally { submit.disabled = false; }
  });
  $("pair-link").addEventListener("input", () => {
    try {
      const link = new URL($("pair-link").value);
      const encoded = link.searchParams.get("data") || "";
      const payload = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(encoded.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0))));
      $("pair-preview").textContent = `目标：${payload.machineName} · ${payload.endpoint}`;
    } catch { $("pair-preview").textContent = "粘贴有效链接后将在这里显示目标地址；不要提交来源不明的链接。"; }
  });
  $("pair-form").addEventListener("submit", async event => {
    event.preventDefault();
    if (!confirm(`${$("pair-preview").textContent}\n确定向此地址提交配对请求？`)) return;
    const submit = event.submitter || $("pair-form").querySelector("button.primary");
    submit.disabled = true; $("pair-result").textContent = "等待目标电脑确认，请不要重复提交…";
    try {
      await api("/api/machines/pair", {link: $("pair-link").value});
      $("pair-link").value = ""; $("pair-preview").textContent = ""; $("pair-result").textContent = "配对和连接已保存。";
      await loadMachines(); await refreshCatalog();
    } catch (failure) { $("pair-result").textContent = `${failure.message} 配对可能已被消费；请在目标电脑检查，不要自动重试。`; }
    finally { submit.disabled = false; }
  });
  for (const node of document.querySelectorAll("[data-close]")) node.addEventListener("click", () => $(node.dataset.close).close());
  $("details-toggle").addEventListener("click", () => {
    const closed = $("app").classList.contains("details-closed");
    if (closed || (innerWidth <= 1120 && !$("app").classList.contains("details-open"))) openDetails();
    else { $("app").classList.add("details-closed"); $("app").classList.remove("details-open"); }
  });
  $("details-close").addEventListener("click", () => { $("app").classList.add("details-closed"); $("app").classList.remove("details-open"); });
  $("new-messages").addEventListener("click", () => { $("timeline").scrollTop = $("timeline").scrollHeight; $("new-messages").hidden = true; });
  $("machine-filter").addEventListener("change", renderList); $("search").addEventListener("input", renderList);
  document.addEventListener("keydown", event => { if ((event.ctrlKey || event.metaKey) && event.key === "k") { event.preventDefault(); $("search").focus(); } });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      closeTools();
      if (selected) { history(selected).version++; renderTimeline(); }
      renderDetails(); return;
    }
    if (selected) { history(selected).online = false; renderHeader(); detailSignature = ""; renderDetails(); }
    refreshCatalog(); pollJournal();
  });
  setInterval(() => {
    if (selected && !document.hidden && Date.now() - history(selected).readAt >= 15000) {
      renderHeader(); renderDetails();
    }
  }, 1000);
  async function start() {
    signedIn = true;
    try { await loadMachines(); await refreshCatalog(); pollJournal(); }
    catch (failure) {
      if (failure.status !== 401) { error(failure.message); if (signedIn) loginNeeded(); }
    }
  }
  start();
})();
