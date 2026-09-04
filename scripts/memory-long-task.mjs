#!/usr/bin/env node
// 长任务 + 长期记忆（浏览器模拟 WS）：多轮长会话（工具链 + 中途写记忆 + 会话内召回）+ 跨会话召回 + 两层落位。
import process from "node:process"; import os from "node:os"; import path from "node:path"; import fs from "node:fs";
const argv = process.argv.slice(2);
const argValue = (n, d) => { const i = argv.indexOf("--" + n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WS_URL = argValue("ws", "ws://localhost:8080/ws/chat");
const TIMEOUT = Number(argValue("timeout", 150000));
const KEEP = argv.includes("--keep");
const GLOBAL_DIR = path.join(os.homedir(), ".agent", "memory");
const PROJ_DIR = path.join("/tmp", ".agent", "memory");
const sleep = ms => new Promise(r => setTimeout(r, ms));
function wipe(d) { if (!fs.existsSync(d)) return; for (const f of fs.readdirSync(d)) { if (/\\.(md|tmp)$/i.test(f)) fs.rmSync(path.join(d, f), { force: true }); } }
class Client { constructor() { this.q = []; this.w = []; this.events = []; }
  connect() { return new Promise((res, rej) => { this.ws = new WebSocket(WS_URL);
    this.ws.onopen = res; this.ws.onerror = () => rej(new Error("ws error"));
    this.ws.onmessage = ev => { let m; try { m = JSON.parse(ev.data); } catch { return; }
      this.events.push(m); const w = this.w.find(x => x.pred(m));
      if (w) { this.w.splice(this.w.indexOf(w), 1); clearTimeout(w.t); w.res(m); } else this.q.push(m); };
    this.ws.onclose = () => { for (const w of this.w.splice(0)) { clearTimeout(w.t); w.rej(new Error("closed")); } }; }); }
  send(o) { this.ws.send(JSON.stringify(o)); }
  expect(pred, ms = TIMEOUT) { const i = this.q.findIndex(pred); if (i >= 0) return Promise.resolve(this.q.splice(i, 1)[0]);
    return new Promise((res, rej) => { const t = setTimeout(() => rej(new Error("timeout")), ms);
      this.w.push({ pred, res: m => { clearTimeout(t); res(m); }, rej, t }); }); }
  close() { try { this.ws.close(); } catch {} }
  newEventsSince(n) { const evs = this.events.slice(n); const toolText = evs.filter(m => m.type === "agent_event" && (m.status === "TOOL_STREAMING" || m.status === "TOOL_FINISHED"))
    .map(m => String(m.content || "")).join(" | ");
    const toolCount = evs.filter(m => m.type === "agent_event" && (m.status === "TOOL_STREAMING" || m.status === "TOOL_FINISHED")).length;
    return { toolText, toolCount }; } }
async function startLong(goal) {
  const c = new Client(); await c.connect(); c.base = 0;
  const sp = c.expect(m => m.type === "session_started");
  c.send({ type: "start_session", requestId: `r${Date.now()}`, goal, workspace: "/tmp" });
  const started = await sp;
  (async () => { for (;;) { try { const req = await c.expect(m => m.type === "permission_requested", 60000);
    c.send({ type: "permission_respond", requestId: `p${Date.now()}`, runId: started.runId, toolCallId: req.toolCallId, toolName: req.toolName, decision: "APPROVED" });
  } catch { return; } } })();
  const term = await c.expect(m => ["done", "error", "stopped", "interrupted"].includes(m.type));
  const fin = c.events.filter(m => m.type === "agent_event" && m.status === "RESPONSE_FINISHED").pop();
  const { toolText, toolCount } = c.newEventsSince(0); c.base = c.events.length;
  return { c, runId: started.runId, terminal: term.type, content: fin?.content || "", toolText, toolCount }; }
async function turn(client, runId, content) {
  const base = client.events.length;
  client.send({ type: "chat", requestId: `r${Date.now()}`, runId, content });
  const term = await client.expect(m => ["done", "error", "stopped", "interrupted"].includes(m.type));
  const fin = client.events.filter(m => m.type === "agent_event" && m.status === "RESPONSE_FINISHED").pop();
  const { toolText, toolCount } = client.newEventsSince(base);
  return { terminal: term.type, content: fin?.content || "", toolText, toolCount }; }
async function oneShot(goal) { const s = await startLong(goal); s.c.close(); return s; }
const NEG = /(没有|未记录|未提及|没记录|不记得|并不知道|不确定|无相关|没有相关|并未|缺少|找不到|没找到|没有关于|并不|而非|不是)/;
const genuine = (answer, re) => String(answer || "").split(/[。\\n；;！!？?]+/).map(s => s.trim()).filter(Boolean).some(s => re.test(s) && !NEG.test(s));
const results = []; const metrics = { totalToolCalls: 0, turnsA: 0 };
const ok = (group, name, cond, detail = "") => { results.push({ group, name, pass: !!cond });
  console.error(`  ${cond ? "PASS" : "FAIL"}  [${group}] ${name}${detail ? "  → " + detail : ""}`); };
wipe(GLOBAL_DIR); wipe(PROJ_DIR); fs.mkdirSync(GLOBAL_DIR, { recursive: true });
console.error("\\n===== 阶段1 长任务会话 A：写记忆 + 工具链 + 会话内召回（多轮）=====");
const A = await startLong("请记住一条项目长期记忆：本项目发布必须走灰度发布流程，先放 10% 量再全量。用 memory_write 工具，type=project，name=lt-release。完成后只回复：已记住");
metrics.turnsA++; metrics.totalToolCalls += A.toolCount;
ok("A1-write-project", "lt-release", A.terminal === "done" && /memory_write/.test(A.toolText), `term=${A.terminal} tool=${A.toolCount} toolHasWrite=${/memory_write/.test(A.toolText)}`);
const t2 = await turn(A.c, A.runId, "继续长任务：①用 shell 创建 /tmp/lt-files/note.txt 内容 LT_CHAIN_A；②创建 /tmp/lt-files/second.txt 内容 LT_CHAIN_B；③再读取两个文件核对。最后只回复：链完成");
metrics.turnsA++; metrics.totalToolCalls += t2.toolCount;
ok("A2-chain-files", ">=3 工具", t2.terminal === "done" && t2.toolCount >= 3, `term=${t2.terminal} tool=${t2.toolCount}`);
const t3 = await turn(A.c, A.runId, "继续：用 shell 依次执行 echo LT_STEP_1、date、echo LT_STEP_2，把输出拼一行报告，最后回复：步骤完成");
metrics.turnsA++; metrics.totalToolCalls += t3.toolCount;
ok("A3-chain-shell", ">=3 工具", t3.terminal === "done" && t3.toolCount >= 3, `term=${t3.terminal} tool=${t3.toolCount}`);
const t4 = await turn(A.c, A.runId, "再记一条用户长期记忆：我写的会议纪要默认用中文。用 memory_write，type=user，name=lt-notes。完成后只回复：已记住");
metrics.turnsA++; metrics.totalToolCalls += t4.toolCount;
ok("A4-write-user", "lt-notes", t4.terminal === "done" && /memory_write/.test(t4.toolText), `term=${t4.terminal} tool=${t4.toolCount} toolHasWrite=${/memory_write/.test(t4.toolText)}`);
const t5 = await turn(A.c, A.runId, "继续：用 shell 统计 /root/core/projects/AgentCode/src/main/java 下面的 .java 文件数量，再看 com/agentcode/hooks 目录下有哪几个 Java 类，汇总成两行输出。最后回复：分析完成");
metrics.turnsA++; metrics.totalToolCalls += t5.toolCount;
ok("A5-repo-analysis", "有工具", t5.terminal === "done" && t5.toolCount >= 1, `term=${t5.terminal} tool=${t5.toolCount}`);
const t6 = await turn(A.c, A.runId, "不查文件、不要猜：根据我们这次会话前面聊过的约定，这个项目发布走什么流程？只回答流程要点。");
metrics.turnsA++; metrics.totalToolCalls += t6.toolCount;
ok("A6-same-session-recall", "灰度/10%", t6.terminal === "done" && genuine(t6.content, /灰度|10%/), `ans=${JSON.stringify(t6.content.slice(0, 60))}`);
A.c.close();
console.error(`\\n===== 会话 A 轮次=${metrics.turnsA} 工具调用=${metrics.totalToolCalls} =====`);
console.error("\\n===== 阶段2 全新会话 B：跨会话召回 =====");
const b1 = await oneShot("根据你系统提示中的长期记忆索引（拿不准就调用 memory_search）：这个 /tmp 工作区项目的发布流程约定是什么？只回答流程要点。");
ok("B1-cross-session-release", "灰度/10%", genuine(b1.content, /灰度|10%/), `ans=${JSON.stringify(b1.content.slice(0, 60))} tool=${b1.toolCount}`);
const b2 = await oneShot("根据长期记忆：我写的会议纪要习惯用什么语言？只回答语言。");
ok("B2-cross-session-notes", "中文", genuine(b2.content, /中文/), `ans=${JSON.stringify(b2.content.slice(0, 60))}`);
console.error("\\n===== 阶段3 文件落位断言 =====");
const pIndex = (d) => fs.existsSync(path.join(d, "MEMORY.md")) ? fs.readFileSync(path.join(d, "MEMORY.md"), "utf8") : "";
ok("C-placement", "project-lt-release", fs.existsSync(path.join(PROJ_DIR, "lt-release.md")) && pIndex(PROJ_DIR).includes("lt-release.md"), "");
ok("C-placement", "user-lt-notes", fs.existsSync(path.join(GLOBAL_DIR, "lt-notes.md")) && pIndex(GLOBAL_DIR).includes("lt-notes.md"), "");
const failed = results.filter(r => !r.pass);
console.error(`\\n===== 汇总 ${results.length - failed.length}/${results.length} PASS =====`);
console.log(JSON.stringify({ ws: WS_URL, metrics, failures: failed.map(r => `${r.group}/${r.name}`), pass: results.length - failed.length, total: results.length }, null, 2));
if (!KEEP) { wipe(GLOBAL_DIR); wipe(PROJ_DIR); }
process.exit(failed.length ? 1 : 0);