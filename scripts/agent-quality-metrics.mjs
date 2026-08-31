#!/usr/bin/env node
// Quantitative Agent quality metrics harness.
// Runs a deterministic small task suite over the browser WebSocket protocol,
// then parses app DEBUG logs and returns aggregated metrics.
import fs from 'node:fs';
import process from 'node:process';

const args = process.argv.slice(2);
function argValue(name, fallback) {
  const idx = args.indexOf('--' + name);
  return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}
const WS_URL = argValue('ws', 'ws://localhost:18080/ws/chat');
const LOG = argValue('log', 'logs/agentcode-metrics2-18080.log');
const TIMEOUT = Number(argValue('timeout', 45000));

class Client {
  constructor(url) {
    this.url = url;
    this.buffer = [];
    this.waiters = [];
    this.finalText = '';
    this.permissionCount = 0;
  }
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error('ws error'));
      this.ws.onmessage = (e) => {
        try { this._push(JSON.parse(e.data)); } catch { /* ignore */ }
      };
      this.ws.onclose = () => {
        const err = new Error('closed');
        this.waiters.forEach(w => w.reject(err));
        this.waiters = [];
      };
    });
  }
  _push(msg) {
    if (msg.type === 'agent_event' && msg.status === 'RESPONSE_FINISHED') {
      this.finalText += (msg.content || '');
    }
    const remain=[]; let consumed=false;
    for (const w of this.waiters) {
      if (!consumed && w.pred(msg)) { clearTimeout(w.timer); w.resolve(msg); consumed=true; } else remain.push(w);
    }
    this.waiters = remain;
    if (!consumed) this.buffer.push(msg);
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  close() { try { this.ws.close(); } catch {} }
  next(pred, timeout = TIMEOUT) {
    const idx = this.buffer.findIndex(pred);
    if (idx >= 0) return Promise.resolve(this.buffer.splice(idx,1)[0]);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.waiters = this.waiters.filter(x => x.resolve !== resolve); reject(new Error('ws timeout')); }, timeout);
      this.waiters.push({ pred, resolve: (m) => { clearTimeout(timer); resolve(m); }, reject });
    });
  }
  async startSession(goal, workspace, approve='APPROVED') {
    const t0 = Date.now();
    const runId = await new Promise((resolve,reject)=>{
      this.send({ type:'start_session', requestId:`q${Date.now()}`, goal, workspace });
      this.next(m => m.type === 'session_started').then(m=>resolve(m.runId)).catch(reject);
    });
    this.lastMsg = null;
    const terminal = await this.waitTerminal(runId, approve);
    return { runId, durationMs: Date.now() - t0, finalText: this.finalText, permissionCount: this.permissionCount, terminal };
  }
  async waitTerminal(runId, approve) {
    for (;;) {
      const msg = await this.next(m => ['done','error','stopped','interrupted','permission_requested','permission_pending'].includes(m.type));
      if (msg.type === 'permission_requested') {
        this.permissionCount++;
        this.send({ type:'permission_respond', requestId:`p${Date.now()}`, runId, toolCallId: msg.toolCallId, decision: approve, arguments: msg.arguments });
        continue;
      }
      if (msg.type === 'permission_pending') {
        try { JSON.parse(msg.content||'[]').forEach(id => this.send({ type:'permission_respond', requestId:`pp${Date.now()}`, runId, toolCallId:id, decision:approve })); } catch {}
        continue;
      }
      if (msg.type === 'agent_event' && msg.status === 'RESPONSE_FINISHED') this.finalText += (msg.content||'');
      return msg;
    }
  }
}

async function startTask(client, goal, workspace, approve) {
  return await client.startSession(goal, workspace, approve);
}

const tasks = [
  { name: 'text_1', type: 'text', goal: '请只回复 PASS_TEXT_1，不要使用任何工具', expected: /PASS_TEXT_1/ },
  { name: 'text_2', type: 'text', goal: '请只回复 PASS_TEXT_2，不要使用任何工具', expected: /PASS_TEXT_2/ },
  { name: 'math', type: 'text', goal: '计算 12+30，只回复数字结果，不要使用工具', expected: /42/ },
  { name: 'chinese', type: 'text', goal: '请把“今天天气很好”翻译成英文，只输出翻译，不要使用工具', expected: /today|weather|good|nice/i },
  { name: 'tool_echo_1', type: 'tool_success', goal: '请调用 shell 工具执行命令：echo PASS_TOOL_1，执行完成后回复完成', expected: /完成|ok|done/i },
  { name: 'tool_echo_2', type: 'tool_success', goal: '请调用 shell 工具执行命令：echo PASS_TOOL_2，执行完成后回复完成', expected: /完成|ok|done/i },
  { name: 'tool_failure', type: 'tool_failure', goal: '请调用 shell 工具执行命令：bash -c "exit 3"，执行完成后回复完成', expected: /完成|ok|done/i },
  { name: 'write_file', type: 'file', goal: '请调用 write_file 工具，在文件 metric_file.txt 中写入 PASS_FILE_1，然后回复完成', file: '/tmp/agentcode-metric-file/metric_file.txt', expected: 'PASS_FILE_1' },
  { name: 'outside_approve', type: 'human', goal: '请调用 shell 工具执行命令：cat /etc/hosts，执行完成后回复完成', expected: /完成|ok|done/i },
  { name: 'danger_approve', type: 'human', goal: '请调用 shell 工具执行命令：rm -rf /tmp/agentcode-nonexistent-file，执行完成后回复完成', expected: /完成|ok|done/i },
  { name: 'multi_tool', type: 'tool_success', goal: '请先后调用 shell 工具执行命令：echo PASS_MULTI_1，然后 echo PASS_MULTI_2，执行完成后回复完成', expected: /完成|ok|done/i },
  { name: 'outside_write', type: 'human', goal: '请调用 write_file 工具，把 PASS_OUTSIDE 写入绝对路径 /tmp/agentcode-metric-outside.txt，然后回复完成', expected: /完成|ok|done/i },
  { name: 'long_answer', type: 'text', goal: '请简单解释 Redis 的 AOF，写约120字，不要使用工具', expected: /.{20,}/ },
];

function parseTs(line) {
  const m = line.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2})/);
  return m ? new Date(m[1]).getTime() : null;
}
function parseLog(log, startMs, endMs) {
  const lines = log.split('\n');
  let aiCalls = 0, tokens = 0, promptTokens = 0, completionTokens = 0;
  let toolSuccess = 0, toolFailure = 0, retry = 0;
  let maxMessages = 0, maxCallNo = 0;
  for (let i = 0; i < lines.length; i++) {
    const ts = parseTs(lines[i]);
    if (ts == null || ts < startMs - 50 || ts > endMs + 50) continue;
    const line = lines[i];
    const m = line.match(/AUDIT_(AI_STREAM|AI_CALL).*promptTokens=(\d+).*completionTokens=(\d+).*totalTokens=(\d+)/);
    if (m) { aiCalls++; promptTokens += Number(m[2]); completionTokens += Number(m[3]); tokens += Number(m[4]); }
    const modelCall = line.match(/AUDIT_MODEL_CALL_COMPLETED runId=[^ ]+ callNo=(\d+)/);
    if (modelCall) maxCallNo = Math.max(maxCallNo, Number(modelCall[1]));
    const tool = line.match(/AUDIT_TOOL_METRICS runId=([^ ]+) tool=([^ ]+).*status=([^ ]+) error=(true|false) result=(.*)$/);
    if (tool) {
      const err = tool[4] === 'true';
      const exitBad = /Exit code: [1-9]\d*/.test(tool[5]);
      if (err || exitBad) toolFailure++; else toolSuccess++;
    }
    if (/Tool .* execution failed|execution failed, handling|parallel tool execution completed.*[1-9]\d* failures|using error fallback/.test(line)) toolFailure++;
    if (/retry|Retry|retrying|retryNumber/.test(line) && !/retryableExceptionPredicate|ModelRetryInterceptor\.java|ToolRetryInterceptor\.java/.test(line)) retry++;
    const msg = line.match(/messages=size=(\d+)/);
    if (msg) maxMessages = Math.max(maxMessages, Number(msg[1]));
  }
  return { aiCalls, tokens, promptTokens, completionTokens, toolSuccess, toolFailure, retry, maxMessages, maxCallNo };
}

async function runTask(task) {
  const fsMod = await import('node:fs');
  fsMod.mkdirSync('/tmp/agentcode-metric-file', { recursive: true });
  try { fsMod.unlinkSync('/tmp/agentcode-metric-file/metric_file.txt'); } catch {}
  const c = new Client(WS_URL);
  await c.connect();
  const startMs = Date.now();
  let result;
  try {
    result = await c.startSession(task.goal, '/tmp/agentcode-metric-file');
  } finally {
    c.close();
  }
  const endMs = Date.now();
  let pass = result.terminal.type === 'done';
  let detail = {};
  if (task.type === 'text' || task.type === 'tool_success' || task.type === 'human' || task.type === 'tool_failure') {
    pass = pass && task.expected.test(result.finalText || '');
  }
  if (task.type === 'file') {
    let content = '';
    try { content = fsMod.readFileSync(task.file, 'utf8'); } catch (e) { content = ''; }
    pass = pass && content.includes(task.expected);
    detail.file = task.file; detail.content = content.slice(0, 100);
  }
  if (task.type === 'human') {
    pass = pass && result.permissionCount > 0;
    detail.permissionCount = result.permissionCount;
  }
  return { ...task, ...result, pass, ...detail, startMs, endMs };
}

async function main() {
  const runResults = [];
  for (const task of tasks) {
    try {
      runResults.push(await runTask(task));
      process.stderr.write(`completed ${task.name} pass=${runResults.at(-1).pass}\n`);
    } catch (e) {
      runResults.push({ ...task, pass: false, error: e.message, terminal: { type: 'exception' }, permissionCount: 0 });
      process.stderr.write(`failed ${task.name}: ${e.message}\n`);
    }
  }
  const log = fs.readFileSync(LOG, 'utf8');
  let totalAiCalls = 0, totalTokens = 0, totalPrompt = 0, totalCompletion = 0, totalToolSuccess = 0, totalToolFailure = 0, totalRetry = 0, totalSteps = 0, driftWarnings = 0;
  for (const r of runResults) {
    const p = parseLog(log, r.startMs, r.endMs);
    r.metrics = p;
    totalAiCalls += p.aiCalls; totalTokens += p.tokens; totalPrompt += p.promptTokens; totalCompletion += p.completionTokens;
    totalToolSuccess += p.toolSuccess; totalToolFailure += p.toolFailure; totalRetry += p.retry;
    const steps = p.maxCallNo; totalSteps += steps;
    if (steps > 8 || p.maxMessages > 18 || (steps > 3 && p.aiCalls > 4)) driftWarnings++;
  }
  const n = runResults.length;
  const successTasks = runResults.filter(r => r.terminal.type === 'done').length;
  const passed = runResults.filter(r => r.pass).length;
  const human = runResults.filter(r => r.permissionCount > 0).length;
  const toolCalls = totalToolSuccess + totalToolFailure;
  const report = {
    tasks: n,
    taskSuccessRate: Number((successTasks / n * 100).toFixed(2)),
    averageSteps: Number((totalSteps / n).toFixed(2)),
    averageTokensPerTask: Number((totalTokens / n).toFixed(2)),
    averageTokensPerAiCall: totalAiCalls ? Number((totalTokens / totalAiCalls).toFixed(2)) : 0,
    totalAiCalls,
    promptTokens: totalPrompt,
    completionTokens: totalCompletion,
    toolFailureRate: toolCalls ? Number((totalToolFailure / toolCalls * 100).toFixed(2)) : 0,
    retryRate: totalAiCalls ? Number((totalRetry / totalAiCalls * 100).toFixed(2)) : 0,
    driftWarningRate: Number((driftWarnings / n * 100).toFixed(2)),
    humanTakeoverRate: Number((human / n * 100).toFixed(2)),
    evaluationPassRate: Number((passed / n * 100).toFixed(2)),
    totals: { totalTokens, totalSteps, totalToolSuccess, totalToolFailure, totalRetry, humanTasks: human },
    tasksDetail: runResults.map(r => ({
      name: r.name, pass: r.pass, terminal: r.terminal.type, permissionCount: r.permissionCount,
      steps: r.metrics?.maxCallNo ?? null, aiCalls: r.metrics?.aiCalls ?? null, tokens: r.metrics?.tokens ?? null,
      toolSuccess: r.metrics?.toolSuccess ?? null, toolFailure: r.metrics?.toolFailure ?? null, retry: r.metrics?.retry ?? null,
      error: r.error || null
    }))
  };
  console.log(JSON.stringify(report, null, 2));
  process.exit(0);
}
main().catch(e => { console.error(e); process.exit(1); });