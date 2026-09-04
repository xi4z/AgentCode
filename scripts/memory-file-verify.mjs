#!/usr/bin/env node
// 文件式长期记忆（feat/rememory 重构）的浏览器模拟验证：WebSocket 协议 + 文件系统断言，不使用测试类。
//
// 验证六件事：
//   V1 工具注册：memory_search / memory_write / memory_forget 都在主 Agent 工具集里
//   V2 写入：声明偏好 → 模型自己调 memory_write → 全局层落文件 + MEMORY.md 出索引行
//   V3 跨会话召回：新会话仅凭会话起点索引快照答出 pnpm
//   V4 无关查询：memory_search 乱查不产生假命中、不带出 pnpm
//   V5 遗忘：memory_forget 后文件与索引行真的消失
//   V6 遗忘后效：再开新会话答不出 pnpm（证明删干净了）
//   另：ES 独立性——全程 agent_memory 索引计数不得变化
//
// 用法: node scripts/memory-file-verify.mjs [--ws ws://localhost:8080/ws/chat] [--es http://localhost:9200]
import process from 'node:process';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';

const argv = process.argv.slice(2);
const argValue = (n, d) => { const i = argv.indexOf('--' + n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WS_URL = argValue('ws', 'ws://localhost:8080/ws/chat');
const ES = argValue('es', 'http://localhost:9200');
const TIMEOUT = Number(argValue('timeout', 150000));
const sleep = ms => new Promise(r => setTimeout(r, ms));

const GLOBAL_DIR = path.join(os.homedir(), '.agent', 'memory');
const INDEX = path.join(GLOBAL_DIR, 'MEMORY.md');

class Client {
  constructor() { this.q = []; this.w = []; }
  connect() {
    return new Promise((res, rej) => {
      this.ws = new WebSocket(WS_URL);
      this.ws.onopen = res;
      this.ws.onerror = () => rej(new Error('ws error'));
      this.ws.onmessage = ev => {
        let m; try { m = JSON.parse(ev.data); } catch { return; }
        const w = this.w.find(x => x.pred(m));
        if (w) { this.w.splice(this.w.indexOf(w), 1); clearTimeout(w.t); w.res(m); } else this.q.push(m);
      };
      this.ws.onclose = () => { for (const w of this.w.splice(0)) { clearTimeout(w.t); w.rej(new Error('closed')); } };
    });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  expect(pred, ms = TIMEOUT) {
    return new Promise((res, rej) => {
      const i = this.q.findIndex(pred);
      if (i >= 0) { res(this.q.splice(i, 1)[0]); return; }
      const t = setTimeout(() => rej(new Error('timeout')), ms);
      this.w.push({ pred, res: m => { clearTimeout(t); res(m); }, rej, t });
    });
  }
  all() { return this.q; }
  close() { try { this.ws.close(); } catch {} }
}

/** 跑一轮浏览器会话（start_session 即开跑），返回最终回答 + 观察到的工具调用文本 */
async function chat(goal) {
  const c = new Client();
  await c.connect();
  const sp = c.expect(m => m.type === 'session_started');
  c.send({ type: 'start_session', requestId: `r${Date.now()}`, goal, workspace: '/tmp' });
  const started = await sp;
  (async () => {
    for (;;) {
      try {
        const req = await c.expect(m => m.type === 'permission_requested', 60000);
        c.send({ type: 'permission_respond', requestId: `p${Date.now()}`, runId: started.runId,
          toolCallId: req.toolCallId, toolName: req.toolName, decision: 'APPROVED' });
      } catch { return; }
    }
  })();
  const term = await c.expect(m => ['done', 'error', 'stopped', 'interrupted'].includes(m.type));
  const events = c.all().filter(m => m.type === 'agent_event');
  const fin = events.filter(m => m.status === 'RESPONSE_FINISHED').pop();
  const toolText = events.filter(m => m.status === 'TOOL_STREAMING' || m.status === 'TOOL_FINISHED')
    .map(m => String(m.content || '')).join(' | ');
  c.close();
  return { runId: started.runId, terminal: term.type, content: fin?.content || '', toolText };
}

const indexLinesMentioning = word => {
  if (!fs.existsSync(INDEX)) return [];
  return fs.readFileSync(INDEX, 'utf8').split('\n')
    .filter(l => l.trim().startsWith('- [') && l.toLowerCase().includes(word.toLowerCase()));
};
const topicFilesMentioning = word => {
  if (!fs.existsSync(GLOBAL_DIR)) return [];
  return fs.readdirSync(GLOBAL_DIR)
    .filter(f => f.endsWith('.md') && f !== 'MEMORY.md')
    .filter(f => fs.readFileSync(path.join(GLOBAL_DIR, f), 'utf8').toLowerCase().includes(word.toLowerCase()));
};
const esCount = async () => {
  try { const r = await fetch(`${ES}/agent_memory/_count`); return r.ok ? (await r.json()).count : -1; }
  catch { return -1; }
};

const failures = [];
const ok = (name, cond, detail) => {
  console.error(`  ${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  → ' + detail : ''}`);
  if (!cond) failures.push(name);
};

// 前置：测试记忆目录必须干净（该目录是本验证专用的全局层；不存在也视为干净）
for (const f of fs.existsSync(GLOBAL_DIR) ? fs.readdirSync(GLOBAL_DIR) : []) {
  if (/\.(md|tmp)$/i.test(f)) fs.rmSync(path.join(GLOBAL_DIR, f), { force: true });
}
const esBefore = await esCount();

// ---- V1 工具注册 ----
console.error('\n[V1] memory_* 三工具是否都挂到主 Agent');
const v1 = await chat('只回答、不要调用任何工具：你的可用工具里与长期记忆相关的工具有哪些？用逗号分隔列出完整工具名。');
const v1c = v1.content.toLowerCase();
ok('V1_tools_registered', ['memory_search', 'memory_write', 'memory_forget'].every(t => v1c.includes(t)),
   `ans="${v1.content.slice(0, 90).replace(/\s+/g, ' ')}"`);

// ---- V2 写入：模型自己调 memory_write，全局层落盘 ----
console.error('\n[V2] 声明偏好 → memory_write 落文件 + 索引');
const v2 = await chat('这是一条关于我的长期偏好声明：我装 JS 依赖统一用 pnpm，不用 npm。'
  + '请用 memory_write 工具把它记下来（type=user，name 自拟），完成后只回复：已记住');
let wrote = false;
for (let i = 0; i < 12 && !wrote; i++) {
  wrote = indexLinesMentioning('pnpm').length > 0 && topicFilesMentioning('pnpm').length > 0;
  if (!wrote) await sleep(2500);
}
ok('V2a_tool_called', /memory_write/.test(v2.toolText), `toolSeen=${/memory_write/.test(v2.toolText)}`);
ok('V2b_file_on_disk', wrote,
   `index=${JSON.stringify(indexLinesMentioning('pnpm').map(l => l.slice(0, 60)))} files=${JSON.stringify(topicFilesMentioning('pnpm'))}`);

// ---- V3 新会话仅凭起点快照召回 ----
console.error('\n[V3] 新会话（起点索引快照）答出 pnpm');
const v3 = await chat('我从没在本次对话里提过任何偏好。根据你系统提示中的长期记忆索引（拿不准可以调用 memory_search），'
  + '回答：我装 JS 依赖用什么包管理器？只回答工具名。');
ok('V3_recall_from_index', /pnpm/i.test(v3.content), `ans="${v3.content.slice(0, 60)}" toolSeen=${/memory_search/.test(v3.toolText)}`);

// ---- V4 无关查询不产生假命中 ----
console.error('\n[V4] memory_search 查无关领域不得带出 pnpm');
const v4 = await chat('请调用 memory_search 检索「kubernetes helm chart 灰度发布策略」，把工具原样返回的内容转述给我，不要自行补充。');
ok('V4_no_false_recall', /没有找到|没有相关|未找到|无相关/i.test(v4.content) && !/pnpm/i.test(v4.content),
   `ans="${v4.content.slice(0, 70).replace(/\s+/g, ' ')}"`);

// ---- V5 遗忘：文件与索引行消失 ----
console.error('\n[V5] memory_forget 删除后文件与索引真的消失');
const v5 = await chat('我之前说用 pnpm 的偏好作废了。请找到对应记忆并用 memory_forget 删除它（必要时先用 memory_search 定位文件名），完成后只回复：已删除');
let gone = false;
for (let i = 0; i < 12 && !gone; i++) {
  gone = indexLinesMentioning('pnpm').length === 0 && topicFilesMentioning('pnpm').length === 0;
  if (!gone) await sleep(2500);
}
ok('V5a_forget_called', /memory_forget/.test(v5.toolText), `toolSeen=${/memory_forget/.test(v5.toolText)}`);
ok('V5b_file_removed', gone, `indexLeft=${indexLinesMentioning('pnpm').length} filesLeft=${topicFilesMentioning('pnpm').length}`);

// ---- V6 遗忘后效：新会话再也答不出 ----
console.error('\n[V6] 遗忘后再开新会话不得再答出 pnpm');
const v6 = await chat('根据你系统提示中的长期记忆索引（拿不准可以调用 memory_search），回答：我装 JS 依赖用什么包管理器？如果不知道就说不知道。');
ok('V6_forgotten', !/pnpm/i.test(v6.content), `ans="${v6.content.slice(0, 60)}"`);

// ---- ES 独立性 ----
const esAfter = await esCount();
ok('V7_es_untouched', esBefore === esAfter, `ES agent_memory count ${esBefore} -> ${esAfter}`);

console.log(JSON.stringify({ ws: WS_URL, failures, es: { before: esBefore, after: esAfter } }, null, 2));
process.exit(failures.length ? 1 : 0);
