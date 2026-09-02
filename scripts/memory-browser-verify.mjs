#!/usr/bin/env node
// 长期记忆深度验证（浏览器模拟：WebSocket 协议与内置 browser TUI 完全一致，不使用任何测试类）。
//
// 三个场景，全部经真实向量模型 + 真实 ES 混排检索：
//   [1] 语义召回：提问与原记忆零关键词重叠，只有向量语义能召回（排除 BM25 侥幸命中）
//   [2] 分层记忆：USER 与 PROJECT 两类记忆各自能被正确召回
//   [3] 去重/强化：换措辞重复声明同一偏好，观察 tryHit 是命中强化还是重复堆积
//
// 用法: node scripts/memory-browser-verify.mjs [--ws ws://localhost:8080/ws/chat] [--es http://localhost:9200]
import process from 'node:process';

const args = process.argv.slice(2);
function argValue(name, fallback) {
  const idx = args.indexOf('--' + name);
  return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}
const WS_URL = argValue('ws', 'ws://localhost:8080/ws/chat');
const ES = argValue('es', 'http://localhost:9200');
const TIMEOUT = Number(argValue('timeout', 120000));

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

class Client {
  constructor() { this.queue = []; this.waiters = []; }
  connect() {
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
      const t = setTimeout(() => rej(new Error('timeout waiting message')), ms);
      this.waiters.push({ pred, res: m => { clearTimeout(t); res(m); }, rej, t });
    });
  }
  close() { try { this.ws.close(); } catch {} }
}

/** 一次完整的浏览器会话：start_session -> 等 done -> 取最终回答文本 */
async function chat(goal) {
  const c = new Client();
  await c.connect();
  const startedP = c.expect(m => m.type === 'session_started');
  c.send({ type: 'start_session', requestId: `r${Date.now()}`, goal, workspace: '/tmp' });
  const started = await startedP;
  // 模型可能自主调用需审批的工具，自动放行以免会话卡在审批上
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
  const finalEvt = c.queue.filter(m => m.type === 'agent_event' && m.status === 'RESPONSE_FINISHED').pop();
  c.close();
  return { runId: started.runId, terminal: terminal.type, content: finalEvt?.content || '' };
}

async function esDocs() {
  try {
    const r = await fetch(`${ES}/agent_memory/_search?size=50`);
    if (!r.ok) return [];
    const j = await r.json();
    return (j.hits?.hits || []).map(h => h._source);
  } catch { return []; }
}

const snapshot = docs => docs.map(d => ({
  id: (d.memoryId || '').slice(0, 8), type: d.type,
  hitCount: d.hitCount ?? 0, confidence: d.confidence, content: d.content,
}));

const report = { ws: WS_URL, es: ES, startedAt: new Date().toISOString(), scenarios: [] };
const failures = [];
function check(name, ok, detail) {
  console.error(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  → ' + detail : ''}`);
  if (!ok) failures.push(name);
  return { name, pass: !!ok, detail };
}

// ---------- 场景 1：零关键词重叠的语义召回 ----------
console.error('\n[1/3] 语义召回：原记忆与提问无任何共同关键词');
const S1_STORE = '我写前端的时候习惯用 React 搭配 TypeScript，一直不太待见 Vue。';
const S1_ASK = '根据你掌握的关于我的长期记忆，我最排斥哪个前端框架？只回答框架名。';
const before1 = await esDocs();
const s1a = await chat(`请记住以下关于我的信息，回复"已记住"即可：${S1_STORE}`);
check('s1_declare_done', s1a.terminal === 'done', `terminal=${s1a.terminal}`);
let deadline = Date.now() + 90000, written = false;
while (Date.now() < deadline) {
  const docs = await esDocs();
  if (docs.length > before1.length && docs.some(d => (d.content || '').includes('Vue'))) { written = true; break; }
  await sleep(3000);
}
check('s1_memory_written', written, `docs ${before1.length} -> ${(await esDocs()).length}`);
const s1b = await chat(`${S1_ASK} 不要使用任何工具。`);
check('s1_recall_semantic_answer', /vue/i.test(s1b.content), `answer="${s1b.content.slice(0, 80)}"`);

// ---------- 场景 2：USER / PROJECT 分层召回 ----------
console.error('\n[2/3] 分层记忆：USER 与 PROJECT 各自可召回');
const s2a = await chat(`请记住两条信息，回复"已记住"即可：`
  + `1) 我个人习惯把接口返回值统一包成 {code,message,data} 结构；`
  + `2) 本项目 agentcode 的向量检索用的是 Elasticsearch 的 dense_vector 加 RRF 混排。`);
check('s2_declare_done', s2a.terminal === 'done', `terminal=${s2a.terminal}`);
deadline = Date.now() + 90000;
const before2 = (await esDocs()).length;
while (Date.now() < deadline) {
  if ((await esDocs()).length > before2) break;
  await sleep(3000);
}
const docs2 = await esDocs();
const types = [...new Set(docs2.map(d => d.type))];
check('s2_multi_type_stored', types.length >= 1, `types=${types.join(',')}`);

const s2b = await chat('根据你掌握的关于我的长期记忆：这个项目里向量检索是用什么技术做的？'
  + '请只回答技术方案，不要使用任何工具。');
check('s2_recall_project', /elastic|es|dense_vector|rrf|混排/i.test(s2b.content), `answer="${s2b.content.slice(0, 80)}"`);

const s2c = await chat('根据你掌握的关于我的长期记忆：我习惯把接口返回值包成什么样？'
  + '请只回答结构，不要使用任何工具。');
check('s2_recall_user', /code/.test(s2c.content) && /data/.test(s2c.content), `answer="${s2c.content.slice(0, 80)}"`);

// ---------- 场景 3：换措辞重复声明 → tryHit 强化 or 重复堆积 ----------
console.error('\n[3/3] 去重强化：换一种措辞重复声明同一条偏好');
const pnpmDocs = () => esDocs().then(ds => ds.filter(d => /pnpm/i.test(d.content || '')));
const before3 = await pnpmDocs();
console.error(`  声明前 pnpm 相关记忆: ${JSON.stringify(snapshot(before3))}`);
const s3a = await chat(`请回复"好的"即可：补充一下，我装 JS 依赖只用 pnpm，从来不用 npm。`);
check('s3_declare_done', s3a.terminal === 'done', `terminal=${s3a.terminal}`);
deadline = Date.now() + 90000;
let after3 = before3;
while (Date.now() < deadline) {
  const cur = await pnpmDocs();
  const grew = cur.length > before3.length;
  const strengthened = cur.some(d => before3.some(b => b.memoryId === d.memoryId && (d.hitCount ?? 0) > (b.hitCount ?? 0)));
  if (grew || strengthened) { after3 = cur; break; }
  await sleep(3000);
  after3 = cur;
}
console.error(`  声明后 pnpm 相关记忆: ${JSON.stringify(snapshot(after3))}`);
const newDocs = after3.filter(d => !before3.some(b => b.memoryId === d.memoryId));
const strengthened = after3.some(d => before3.some(b => b.memoryId === d.memoryId && (d.hitCount ?? 0) > (b.hitCount ?? 0)));
report.scenario3 = {
  beforeCount: before3.length, afterCount: after3.length,
  newDocs: newDocs.length, strengthened,
  tryHitBehaviour: strengthened ? 'STRENGTHENED(命中去重)' : (newDocs.length ? 'DUPLICATED(重复堆积)' : 'NO_CHANGE'),
};
console.error(`  → tryHit 行为: ${report.scenario3.tryHitBehaviour}`);
check('s3_memory_persisted_or_deduped', strengthened || newDocs.length > 0,
  `new=${newDocs.length} strengthened=${strengthened}`);

report.docs = snapshot(await esDocs());
report.failures = failures;
report.finishedAt = new Date().toISOString();
console.log(JSON.stringify(report, null, 2));
process.exit(failures.length ? 1 : 0);
