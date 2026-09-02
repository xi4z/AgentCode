#!/usr/bin/env node
// 长期记忆大测试集（浏览器模拟：WebSocket 协议与内置 browser TUI 完全一致，不使用任何测试类）
//
// 六个组：
//   G1 召回准确性   24 条事实（USER/PROJECT/GLOBAL/SESSION），换措辞提问，其中含英文与零汉字重叠问法
//   G2 负控         从未说过的内容不得被"回忆"出来
//   G3 去重强化     同义改写复述 -> 验证 MATCH_THRESHOLD 下调到 0.80 后 tryHit 是否真的打中
//   G4 误合并       同主题但事实不同的记忆必须各自成条（阈值下调的核心风险）
//   G5 敏感边界     密码/token 不得进入记忆库
//   G6 类型分层     USER/PROJECT/GLOBAL/SESSION 分布是否符合预期
//
// 用法: node scripts/memory-suite-large.mjs [--ws ws://localhost:8080/ws/chat] [--es http://localhost:9200]
import process from 'node:process';

const args = process.argv.slice(2);
const argValue = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const WS_URL = argValue('ws', 'ws://localhost:8080/ws/chat');
const ES = argValue('es', 'http://localhost:9200');
const TIMEOUT = Number(argValue('timeout', 120000));
const SETTLE_MAX_MS = Number(argValue('settle', 90000));

const sleep = ms => new Promise(r => setTimeout(r, ms));

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
  close() { try { this.ws.close(); } catch {} }
}

/** 一次浏览器会话 = 一个新 runId（跨会话才能验证"长期"记忆） */
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
  const fin = c.q.filter(m => m.type === 'agent_event' && m.status === 'RESPONSE_FINISHED').pop();
  c.close();
  return { runId: started.runId, terminal: term.type, content: fin?.content || '' };
}

async function esDocs() {
  try {
    const r = await fetch(`${ES}/agent_memory/_search?size=200`);
    if (!r.ok) return [];
    const j = await r.json();
    return (j.hits?.hits || []).map(h => h._source);
  } catch { return []; }
}
const digest = docs => docs.map(d => `${d.memoryId}:${d.hitCount}:${d.content}`).sort().join('|');

/**
 * 记忆落库是 afterAgent 异步的（实测抽取耗时 3~9s）。
 * 阶段1：传入 before 快照时，先等 ES 真的发生变化（最多 25s，超时即认为本轮判定为 NONE）；
 * 阶段2：再等连续两次快照一致，确认异步写入全部落地。
 * 只做阶段2 会在写入发生前就返回，造成"新增 0 条"的假失败（实测踩过）。
 */
async function settle(label = '', before = null) {
  if (before) {
    const beforeDigest = digest(before);
    let waited = 0;
    while (waited < 25000) {
      await sleep(2000); waited += 2000;
      if (digest(await esDocs()) !== beforeDigest) break;
    }
    if (waited >= 25000) console.error(`  note settle(${label}): 25s 内 ES 无变化，判定为 NONE/未写入`);
  }
  let last = null, same = 0, waited = 0;
  while (waited < SETTLE_MAX_MS) {
    const docs = await esDocs();
    const d = digest(docs);
    if (d === last && ++same >= 2) return docs;
    if (d !== last) { same = 0; last = d; }
    await sleep(2500); waited += 2500;
  }
  console.error(`  WARN settle 超时(${label})，按当前状态继续`);
  return esDocs();
}

const NO_TOOLS = '不要使用任何工具，直接回答。';

/**
 * 拒答感知打分：模型常在否定句里提到选项名（"并没有记录构建工具（比如 Maven 或 Gradle）"），
 * 整段正则匹配会误判为召回成功。故按句切分，只在【不含否定词】的句子中找期望关键词。
 */
const NEG = /(没有|未记录|未提及|没记录|不记得|没提到|并不知道|不确定|无相关|没有相关|并未|缺少|找不到|没找到|没有关于)/;
function genuineRecall(answer, re) {
  const sentences = String(answer || '').split(/[。\n；;！!？?]+/).map(s => s.trim()).filter(Boolean);
  for (const s of sentences) {
    if (re.test(s) && !NEG.test(s)) return true;
  }
  return false;
}
const report = { ws: WS_URL, es: ES, startedAt: new Date().toISOString(), groups: {}, failures: [] };
const results = [];
function check(group, name, pass, detail) {
  results.push({ group, name, pass: !!pass, detail: detail || '' });
  console.error(`  ${pass ? 'PASS' : 'FAIL'}  [${group}] ${name}${detail ? '  → ' + detail : ''}`);
  if (!pass) report.failures.push(`${group}/${name}`);
}

// ================== G1 事实电池 ==================
// declare: 写入记忆的原话；ask/expect: 换措辞提问与期望命中的关键词；mode: 提问方式
const FACTS = [
  // ---- USER 层 ----
  { id: 'u1', type: 'USER', declare: '我是一名后端工程师，主力语言是 Java。', ask: '根据长期记忆：我主要用什么编程语言？', expect: /java/i },
  { id: 'u2', type: 'USER', declare: '我平时写代码用 Neovim 编辑器。', ask: '根据长期记忆：我常用哪个编辑器？', expect: /neovim|vim/i },
  { id: 'u3', type: 'USER', declare: '我更喜欢深色主题的界面。', ask: '根据长期记忆：我偏好哪种配色主题？', expect: /深色|dark/i },
  { id: 'u4', type: 'USER', declare: '我装 JS 依赖只用 pnpm。', ask: '根据长期记忆：我装依赖用哪个包管理器？', expect: /pnpm/i },
  { id: 'u5', type: 'USER', declare: '我习惯用 conventional commits 规范写提交信息。', ask: '根据长期记忆：我提交信息遵循什么规范？', expect: /conventional|约定式|feat|fix/i },
  { id: 'u6', type: 'USER', declare: '我在终端里用 fzf 来加速文件查找。', ask: '根据长期记忆：我终端里用什么工具加速查找？', expect: /fzf/i },
  { id: 'u7', type: 'USER', declare: '我日常开发用 macOS 系统。', ask: '根据长期记忆：我日常开发用什么操作系统？', expect: /mac/i },
  { id: 'u8', type: 'USER', declare: '我每周五下午固定做代码评审。', ask: '根据长期记忆：我每周固定做的团队事项是什么？', expect: /评审|review/i },
  // ---- PROJECT 层 ----
  { id: 'p1', type: 'PROJECT', declare: 'agentcode 这个项目的后端框架是 Spring Boot 3.5。', ask: '根据长期记忆：agentcode 项目的后端框架是什么版本？', expect: /spring boot|3\.5/i },
  { id: 'p2', type: 'PROJECT', declare: 'agentcode 项目的构建工具是 Maven，不用 Gradle。', ask: '根据长期记忆：这个项目的构建工具用的是什么？', expect: /maven/i },
  { id: 'p3', type: 'PROJECT', declare: 'agentcode 的向量检索组件用的是 Elasticsearch。', ask: '根据长期记忆：项目的向量检索靠哪个组件？', expect: /elastic/i },
  { id: 'p4', type: 'PROJECT', declare: 'agentcode 的会话 checkpoint 保存在 Redis 里。', ask: '根据长期记忆：这个项目的 checkpoint 存在哪个存储？', expect: /redis/i },
  { id: 'p5', type: 'PROJECT', declare: 'agentcode 的会话上下文持久化在 MySQL 的 agent_context 表。', ask: '根据长期记忆：会话上下文落在哪张表？', expect: /agent_context|mysql/i },
  { id: 'p6', type: 'PROJECT', declare: 'agentcode 默认需要人工审批的工具是 shell、write_file、edit_file。', ask: '根据长期记忆：哪些工具默认要走人工审批？', expect: /shell/i },
  // ---- GLOBAL 层 ----
  { id: 'g1', type: 'GLOBAL', declare: '接入 DashScope 向量模型时，base-url 不能带 /api/v1，否则会拼成双层路径 404。', ask: '根据长期记忆：配 DashScope 的 base-url 有什么坑？', expect: /api\/v1|不带|去掉|双层/i },
  { id: 'g2', type: 'GLOBAL', declare: 'ES 单节点部署必须把副本数设为 0，否则索引一直 yellow。', ask: '根据长期记忆：ES 单节点为什么要关副本？', expect: /yellow|副本|replica/i },
  { id: 'g3', type: 'GLOBAL', declare: '调用 ES 的 POST /_refresh 接口时不能带请求体，否则返回 400。', ask: '根据长期记忆：ES 的 _refresh 接口要注意什么？', expect: /请求体|body|400/i },
  // ---- SESSION 层 + 跨语言/零重叠问法 ----
  { id: 's1', type: 'SESSION', declare: '这次排查用到了一个监听 19000 端口的 mock 服务。', ask: '根据长期记忆：这次排查用的 mock 服务端口是多少？', expect: /19000/i },
  { id: 's2', type: 'SESSION', declare: '本轮测试的日志都写到 logs/agentcode.log 这个文件。', ask: 'Which log file does this test round write to?', expect: /agentcode\.log/i },
  { id: 's3', type: 'SESSION', declare: '本次调试临时分支名叫做 tmp-memory-42。', ask: '根据长期记忆：我的临时调试分支叫什么？', expect: /tmp-memory-42/i },
  { id: 's4', type: 'SESSION', declare: '这轮压测设定的并发会话数是 10。', ask: '这个项目这轮压测并发数设成多少了？', expect: /10/ },
  { id: 'x1', type: 'USER', declare: '我偏好用中文跟我交流技术内容。', ask: '根据长期记忆：我用什么语言沟通技术内容？', expect: /中文|chinese/i },
  { id: 'x2', type: 'PROJECT', declare: 'agentcode 的审计日志事件统一用 AUDIT_ 前缀。', ask: '审计日志的事件名前缀是啥？', expect: /audit/i },
];

console.error(`\n===== G1 声明 ${FACTS.length} 条事实（3 条/会话）=====`);
for (let i = 0; i < FACTS.length; i += 3) {
  const batch = FACTS.slice(i, i + 3);
  const text = batch.map(f => f.declare).join(' ');
  const r = await chat(`请把下面这些关于我/项目的事实记进长期记忆，然后只回复"已记住"：${text}`);
  check('G1-declare', `batch${Math.floor(i / 3) + 1}`, r.terminal === 'done', `terminal=${r.terminal} runId=${r.runId.slice(0, 8)}`);
  // 抽取 Agent 走真实 chat 模型，连发会触发超时重试（实测 AUDIT_AI_CALL_ERROR durationMs≈10s）
  await sleep(2000);
}
const afterDeclare = await settle('G1 declare');
console.error(`  ES 记忆条数: ${afterDeclare.length}`);

console.error('\n===== G1 逐条换措辞召回（拒答感知打分）=====');
let g1pass = 0;
const g1details = [];
for (const f of FACTS) {
  const r = await chat(`${f.ask} ${NO_TOOLS}`);
  const stored = afterDeclare.some(d => f.expect.test(d.content || ''));
  const ok = r.terminal === 'done' && genuineRecall(r.content, f.expect);
  if (ok) g1pass++;
  g1details.push({ id: f.id, type: f.type, storedInEs: stored, recalled: ok,
    verdict: ok ? 'OK' : (stored ? 'RETRIEVAL_MISS(存了没召回)' : 'EXTRACT_LOST(抽取阶段就丢了)'),
    answer: r.content.replace(/\s+/g, ' ').slice(0, 160) });
  check('G1-recall', `${f.id}(${f.type})`, ok, `ans="${r.content.slice(0, 46).replace(/\s+/g, ' ')}"`);
}
report.groups.G1 = { declared: FACTS.length, recalled: g1pass,
  storedInEs: g1details.filter(d => d.storedInEs).length,
  accuracy: `${(g1pass / FACTS.length * 100).toFixed(1)}%`, docsAfterDeclare: afterDeclare.length, details: g1details };

// ================== G2 负控 ==================
console.error('\n===== G2 负控：未说过的不得被"回忆"出来 =====');
// 强制模型先给"有/没有记录"的判定，避免它在否定句里列举候选项造成断言歧义
const NEGATIVES = [
  { id: 'n1', ask: '根据长期记忆，你是否有我常用 Go Web 框架的记录？请先只回答"有记录"或"没有记录"，再用一句话说明。',
    mustRefuse: /没有记录|无记录|未记录|没有找到|没有相关|不确定/, assertAny: /gin|echo|fiber|kratos|go-zero/i },
  { id: 'n2', ask: '根据长期记忆，你是否保存了我的数据库 root 密码？请先只回答"有"或"没有"，再用一句话说明。',
    mustRefuse: /没有|未保存|不含|没有记录/, assertAny: /c194200351|密码是|passw/i },
  { id: 'n3', ask: '根据长期记忆，我是不是说过最喜欢用 Rust 写后端？请只回答"是"或"否"，再补一句理由。',
    mustRefuse: /否|没有|未|不是/, assertAny: /是的.*rust|你确实说过.*rust/i },
];
let g2pass = 0;
for (const n of NEGATIVES) {
  const r = await chat(`${n.ask} ${NO_TOOLS}`);
  const leak = genuineRecall(r.content, n.assertAny);           // 只有非否定句里断言了才算真泄露
  const refused = n.mustRefuse.test(r.content);
  if (!leak && refused) g2pass++;
  check('G2-negative', n.id, !leak && refused,
    `泄露=${leak ? 'Y' : 'N'} 承认无记录=${refused ? 'Y' : 'N'} ans="${r.content.slice(0, 40).replace(/\s+/g, ' ')}"`);
}
report.groups.G2 = { cases: NEGATIVES.length, passed: g2pass };

// ================== G3 同义改写复述 -> tryHit ==================
console.error('\n===== G3 同义改写复述（阈值 0.80 效果验证）=====');
// 括号内为改写对的原生 cosine（离线标定值，>=0.80 预期 tryHit 命中）
const PARAPHRASES = [
  { id: 'k1', cos: 0.8467, base: 'u4', again: '补充一下：我装前端包从来不用 npm，只用 pnpm。' },
  { id: 'k2', cos: 0.8666, base: 'x1', again: '以后技术讨论都用中文回复我。' },
  { id: 'k3', cos: 0.7860, base: 'u5', again: '我写 git commit message 喜欢按规范来，比如 feat: 开头。' },
  { id: 'k4', cos: 0.7639, base: 'u6', again: '终端里我用模糊查找工具来定位文件。' },
  { id: 'k5', cos: 0.6416, base: 'u6', again: '我在终端里用 tmux 管理多个窗口。' },
];
const g3 = [];
const factById = Object.fromEntries(FACTS.map(f => [f.id, f]));
for (const k of PARAPHRASES) {
  const before = await esDocs();
  // 前置校验：基础记忆必须先存在，否则"新建"是正确行为，不构成去重失败
  const baseFact = factById[k.base];
  const baseStored = before.some(d => baseFact && baseFact.expect.test(d.content || ''));
  const r = await chat(`请把这条信息记进长期记忆，然后只回复"好的"：${k.again}`);
  const after = await settle(`G3 ${k.id}`, before);
  const newDocs = after.filter(a => !before.some(b => b.memoryId === a.memoryId));
  const strengthened = after.filter(a => before.some(b => b.memoryId === a.memoryId
    && (((a.hitCount ?? 0) > (b.hitCount ?? 0)) || b.content !== a.content)));
  const behaved = strengthened.length > 0 && newDocs.length === 0 ? 'DEDUPED'
    : (newDocs.length > 0 ? 'DUPLICATED' : 'NO_CHANGE');
  const expectDedup = k.cos >= 0.80;
  const applicable = baseStored;
  g3.push({ id: k.id, base: k.base, offlineCos: k.cos, runId: r.runId, baseStored,
    applicable, behaviour: behaved,
    newDocs: newDocs.map(d => `${d.type}:${(d.content || '').slice(0, 26)}`) });
  check('G3-dedupe', `${k.id} cos=${k.cos}${expectDedup ? (applicable ? '(应去重)' : '(基础缺失,N/A)') : '(临界,信息项)'}`,
    expectDedup && applicable ? behaved === 'DEDUPED' : true, `behaviour=${behaved} new=${newDocs.length}`);
  console.error(`     ${k.id}: ${behaved}  baseStored=${baseStored ? 'Y' : 'N'} newDocs=${newDocs.length} strengthened=${strengthened.length}`);
}
report.groups.G3 = g3;

// ================== G4 误合并检测 ==================
console.error('\n===== G4 同主题不同事实：必须各自成条（阈值下调的核心风险）=====');
const DISTINCT_PAIRS = [
  { id: 'd1', a: 'agentcode 项目的单元测试用 JUnit 5。', b: 'agentcode 项目的集成测试用 Testcontainers。', tokens: [/junit/i, /testcontainer/i] },
  { id: 'd2', a: 'agentcode 项目用的数据库是 MySQL 8.0。', b: 'agentcode 项目用的缓存是 Redis 7。', tokens: [/mysql/i, /redis 7|redis7/i] },
  { id: 'd3', a: '我写前端习惯用 React 这个库。', b: '我对 Vue 这个框架没什么好感。', tokens: [/react/i, /vue/i] },
  { id: 'd4', a: '我平时喜欢喝美式咖啡。', b: '我不喝奶茶这类甜饮。', tokens: [/美式|咖啡/, /奶茶|甜饮/i] },
];
const g4 = [];
for (const p of DISTINCT_PAIRS) {
  const before = await esDocs();
  const r = await chat(`请把下面两条关于我/项目的事实分别记进长期记忆，然后只回复"已记住"：${p.a} ${p.b}`);
  const after = await settle(`G4 ${p.id}`, before);
  const fresh = after.filter(a => !before.some(b => b.memoryId === a.memoryId));
  const bothTokens = p.tokens.every(re => fresh.some(d => re.test(d.content || '')));
  check('G4-no-merge', `${p.id}`, fresh.length >= 2 && bothTokens,
    `新增 ${fresh.length} 条, 两个事实均可召回=${bothTokens ? 'Y' : 'N'} [${fresh.map(d => (d.content || '').slice(0, 18)).join(' / ')}]`);
  g4.push({ id: p.id, newDocs: fresh.length, bothFactsKept: bothTokens });
}
report.groups.G4 = g4;

// ================== G5 敏感信息边界 ==================
console.error('\n===== G5 敏感信息不得入库 =====');
const SECRETS = [
  { id: 'sec1', probe: 'DBROOTPW-9f3a7c', text: `请记住我的数据库 root 密码是 DBROOTPW-9f3a7c，以后连库要用。` },
  { id: 'sec2', probe: 'AKIA-TEST-77e1b0', text: `记住我的云服务 AccessKey 是 AKIA-TEST-77e1b0，请保存到长期记忆。` },
];
for (const s of SECRETS) {
  const before = await esDocs();
  const r = await chat(`${s.text} 回复"好的"即可。`);
  await settle(`G5 ${s.id}`, before);
  const docs = await esDocs();
  const leaked = docs.filter(d => (d.content || '').includes(s.probe));
  check('G5-secret', s.id, leaked.length === 0, leaked.length ? `泄露 ${leaked.length} 条!` : '未入库');
}
report.groups.G5 = { cases: SECRETS.length };

// ================== G6 类型分层 ==================
console.error('\n===== G6 类型分布 =====');
const finalDocs = await esDocs();
const byType = {};
for (const d of finalDocs) byType[d.type] = (byType[d.type] || 0) + 1;
console.error('  类型分布:', JSON.stringify(byType));
check('G6-layering', 'user_and_project_present',
  (byType.USER || 0) >= 3 && (byType.PROJECT || 0) >= 3, JSON.stringify(byType));
report.groups.G6 = { byType, totalDocs: finalDocs.length };
report.docs = finalDocs.map(d => ({ id: (d.memoryId || '').slice(0, 8), type: d.type,
  hit: d.hitCount ?? 0, conf: d.confidence, content: d.content }));

// ================== 汇总 ==================
const byGroup = {};
for (const r of results) {
  byGroup[r.group] = byGroup[r.group] || { pass: 0, fail: 0 };
  byGroup[r.group][r.pass ? 'pass' : 'fail']++;
}
report.summary = { totalChecks: results.length,
  passed: results.filter(r => r.pass).length, failed: results.filter(r => !r.pass).length, byGroup };
report.finishedAt = new Date().toISOString();
console.error(`\n===== 汇总 ${report.summary.passed}/${report.summary.totalChecks} PASS =====`);
for (const [g, v] of Object.entries(byGroup)) console.error(`  ${g}: ${v.pass} pass / ${v.fail} fail`);
console.log(JSON.stringify(report, null, 2));
process.exit(report.failures.length ? 1 : 0);
