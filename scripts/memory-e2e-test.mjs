#!/usr/bin/env node
// 长期记忆端到端验证（真实 DeepSeek chat + mock embedding + ES 混排检索）：
//   1. 会话 A 声明一个用户偏好 → run 完成 → MemoryHook.afterAgent 异步抽取+embedding+写入 ES
//   2. 轮询 ES agent_memory 索引直到出现文档
//   3. 会话 B 询问该偏好 → beforeAgent 召回注入 → DeepSeek 结合记忆回答
// 用法: node scripts/memory-e2e-test.mjs [--ws ws://localhost:8080/ws/chat] [--es http://localhost:9200]
import process from 'node:process';

const args = process.argv.slice(2);
function argValue(name, fallback) {
  const idx = args.indexOf('--' + name);
  return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}
const WS_URL = argValue('ws', 'ws://localhost:8080/ws/chat');
const ES = argValue('es', 'http://localhost:9200');
const TIMEOUT = Number(argValue('timeout', 120000));

const PREFERENCE = process.env.MEM_PREF || '用户偏好使用 pnpm 而不是 npm 管理依赖';
const KEYWORD = process.env.MEM_KEYWORD || 'pnpm';

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

class Client {
  constructor() { this.queue = []; this.waiters = []; }
  connect() {
    this._t0 = Date.now();
    return new Promise((res, rej) => {
      this.ws = new WebSocket(WS_URL);
      this.ws.onopen = res;
      this.ws.onerror = () => rej(new Error('ws error'));
      this.ws.onmessage = ev => {
        let m; try { m = JSON.parse(ev.data); } catch { return; }
        const w = this.waiters.find(w => w.pred(m));
        if (w) { this.waiters.splice(this.waiters.indexOf(w), 1); clearTimeout(w.t); w.res(m); }
        else this.queue.push(m);
      };
      this.ws.onclose = () => { for (const w of this.waiters.splice(0)) { clearTimeout(w.t); w.rej(new Error('closed')); } };
    });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  expect(pred, ms = TIMEOUT) {
    return new Promise((res, rej) => {
      const i = this.queue.findIndex(pred);
      if (i >= 0) { res(this.queue.splice(i, 1)[0]); return; }
      const t = setTimeout(() => {
        const wi = this.waiters.indexOf(w);
        if (wi >= 0) this.waiters.splice(wi, 1);
        rej(new Error('timeout waiting message, queue=' + JSON.stringify(this.queue.map(m => ({ dt: Date.now() - (this._t0 || 0), type: m.type, status: m.status })))) + ' wsState=' + this.ws.readyState);
      }, ms);
      const w = { pred: m => { if (pred(m)) { clearTimeout(t); return true; } return false; }, res, rej, t };
      this.waiters.push(w);
    });
  }
  close() { try { this.ws.close(); } catch {} }
}

async function chat(goal) {
  const c = new Client();
  await c.connect();
  const startedP = c.expect(m => m.type === 'session_started');
  c.send({ type: 'start_session', requestId: `r${Date.now()}`, goal, workspace: '/tmp' });
  const started = await startedP;
  // 真实模型可能主动调用需要审批的工具（如 shell）——自动批准，避免会话停在 INTERRUPTED
  (async () => {
    for (;;) {
      try {
        const req = await c.expect(m => m.type === 'permission_requested', 60000);
        c.send({ type: 'permission_respond', requestId: `p${Date.now()}`, runId: started.runId,
          toolCallId: req.toolCallId, toolName: req.toolName, decision: 'APPROVED' });
      } catch { return; }
    }
  })();
  const terminal = await c.expect(m =>
    m.type === 'done' || m.type === 'error' || m.type === 'stopped' || m.type === 'interrupted');
  // 从事件流抓 RESPONSE_FINISHED 的最终文本
  const finalEvt = c.queue.filter(m => m.type === 'agent_event' && m.status === 'RESPONSE_FINISHED').pop();
  c.close();
  return { runId: started.runId, terminal: terminal.type, content: finalEvt?.content || '' };
}

async function esCount() {
  try {
    const r = await fetch(`${ES}/agent_memory/_count`);
    if (r.status === 404) return 0;
    const j = await r.json();
    return j.count ?? 0;
  } catch { return 0; }
}

async function esDocs() {
  try {
    const r = await fetch(`${ES}/agent_memory/_search?size=20`);
    if (!r.ok) return [];
    const j = await r.json();
    return (j.hits?.hits || []).map(h => h._source);
  } catch { return []; }
}

const report = { ws: WS_URL, es: ES, startedAt: new Date().toISOString() };
const failures = [];
const check = (name, ok, detail) => {
  report[name] = { pass: !!ok, ...(detail ? { detail } : {}) };
  console.error(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
  if (!ok) failures.push(name);
};

// 步骤 1：声明偏好
console.error('[1/3] 会话 A：声明用户偏好 → 触发异步记忆抽取');
const r1 = await chat(`请记住以下信息并在以后使用：${PREFERENCE}。现在只需回复“已记住”。`);
check('declare_run_done', r1.terminal === 'done', `terminal=${r1.terminal} runId=${r1.runId}`);

// 步骤 2：轮询 ES（异步抽取+写入）
console.error('[2/3] 轮询 ES agent_memory 等待异步写入...');
let count = 0;
const deadline = Date.now() + 90000;
while (Date.now() < deadline) {
  count = await esCount();
  if (count > 0) break;
  await sleep(3000);
}
check('memory_written_to_es', count > 0, `count=${count}`);
const docs = await esDocs();
report.esDocs = docs.map(d => ({ type: d.type, content: d.content }));
console.error('  ES 文档:', JSON.stringify(report.esDocs, null, 2).slice(0, 800));

// 步骤 3：新会话召回
console.error('[3/3] 会话 B：询问偏好 → 验证召回注入与回答');
const r2 = await chat(`根据你掌握的关于我的长期记忆回答：我平时用什么包管理器？只回答名字。`);
check('recall_run_done', r2.terminal === 'done', `terminal=${r2.terminal} runId=${r2.runId}`);
check('recall_answer_contains_keyword', r2.content.toLowerCase().includes(KEYWORD.toLowerCase()),
  `answer="${r2.content.slice(0, 120)}"`);

report.finishedAt = new Date().toISOString();
report.failures = failures;
console.log(JSON.stringify(report, null, 2));
process.exit(failures.length ? 1 : 0);
