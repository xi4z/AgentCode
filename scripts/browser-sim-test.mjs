#!/usr/bin/env node
// Browser-simulation test harness for AgentCode Java WebSocket endpoint.
// It talks exactly like the bundled browser TUI (no JUnit/Spring test classes).
// Usage: node scripts/browser-sim-test.mjs [--ws ws://localhost:18080/ws/chat] [--timeout 60000]
import process from 'node:process';

const args = process.argv.slice(2);
function argValue(name, fallback) {
  const idx = args.indexOf('--' + name);
  return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}
const WS_URL = argValue('ws', 'ws://localhost:18080/ws/chat');
const GLOBAL_TIMEOUT = Number(argValue('timeout', 60000));

class BrowserClient {
  constructor(url) {
    this.url = url;
    this.ws = null;
    this.buffer = [];
    this.waiters = [];
    this.closed = false;
    this.lastAgentEventAt = null;
    this.lastResponseFinishedAt = null;
  }

  resetMetrics() {
    this.lastAgentEventAt = null;
    this.lastResponseFinishedAt = null;
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (e) => reject(new Error('ws error: ' + (e.message || '')));
      this.ws.onmessage = (event) => {
        let msg;
        try {
          msg = JSON.parse(event.data);
        } catch (e) {
          msg = { type: '__parse_error__', raw: event.data, error: e.message };
        }
        this._push(msg);
      };
      this.ws.onclose = () => {
        this.closed = true;
        const err = new Error('connection closed');
        for (const w of this.waiters.splice(0)) w.reject(err);
      };
    });
  }

  _push(msg) {
    if (msg.type === 'agent_event') {
      this.lastAgentEventAt = Date.now();
      if (msg.status === 'RESPONSE_FINISHED') {
        this.lastResponseFinishedAt = this.lastAgentEventAt;
      }
    }
    const remaining = [];
    let consumed = false;
    for (const w of this.waiters) {
      if (!consumed && w.pred(msg)) {
        clearTimeout(w.timer);
        w.resolve(msg);
        consumed = true;
      } else {
        remaining.push(w);
      }
    }
    this.waiters = remaining;
    if (!consumed) {
      this.buffer.push(msg);
    }
  }

  send(obj) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) throw new Error('ws not open');
    this.ws.send(JSON.stringify(obj));
  }

  next(pred, timeout = GLOBAL_TIMEOUT) {
    const idx = this.buffer.findIndex(pred);
    if (idx >= 0) return Promise.resolve(this.buffer.splice(idx, 1)[0]);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter(w => w.resolve !== resolve);
        reject(new Error(`timeout waiting for WebSocket message, buffer=${JSON.stringify(this.buffer.slice(-5))}`));
      }, timeout);
      this.waiters.push({
        pred,
        resolve: (msg) => { clearTimeout(timer); resolve(msg); },
        reject
      });
    });
  }

  close() {
    try { this.ws.close(); } catch (_) {}
  }

  async startSession(goal, workspace = '/tmp', onPermission = null) {
    const t0 = Date.now();
    this.resetMetrics();
    this.send({ type: 'start_session', requestId: `r${Date.now()}`, goal, workspace });
    const started = await this.next(m => m.type === 'session_started');
    const submitMs = Date.now() - t0;
    const runId = started.runId;
    const terminal = await this.waitTerminal(runId, onPermission);
    return { runId, submitMs, terminal, messages: this.buffer };
  }

  async chat(runId, content, onPermission = null) {
    const t0 = Date.now();
    this.resetMetrics();
    this.send({ type: 'chat', requestId: `r${Date.now()}`, runId, content });
    const terminal = await this.waitTerminal(runId, onPermission);
    return { runId, durationMs: Date.now() - t0, terminal, messages: this.buffer };
  }

  async stop(runId) {
    const t0 = Date.now();
    this.send({ type: 'stop', requestId: `r${Date.now()}`, runId });
    const msg = await this.next(m => m.type === 'stopped' || m.type === 'error');
    return { msg, latencyMs: Date.now() - t0 };
  }

  async interrupt(runId, guidance = '请立即停下') {
    const t0 = Date.now();
    this.send({ type: 'interrupt', requestId: `r${Date.now()}`, runId, guidance });
    const msg = await this.next(m => m.type === 'interrupted' || m.type === 'error');
    return { msg, latencyMs: Date.now() - t0 };
  }

  async waitTerminal(runId, onPermission) {
    for (;;) {
      const msg = await this.next(m =>
        m.type === 'done' || m.type === 'error' || m.type === 'stopped' || m.type === 'interrupted' ||
        m.type === 'permission_requested' || m.type === 'permission_pending');
      if (msg.type === 'permission_requested') {
        if (onPermission) {
          await onPermission(this, runId, msg);
        } else {
          // Auto-approve for latency/concurrency tests unless the test opts out.
          this.send({
            type: 'permission_respond', requestId: `r${Date.now()}`, runId,
            toolCallId: msg.toolCallId, toolName: msg.toolName, decision: 'APPROVED', arguments: msg.arguments
          });
        }
        continue;
      }
      if (msg.type === 'permission_pending') {
        // If a multi-tool round is waiting for the remaining ids, approve them so the
        // browser simulation does not deadlock waiting for events that never repeat.
        try {
          const ids = JSON.parse(msg.content || '[]');
          for (const id of ids) {
            this.send({
              type: 'permission_respond', requestId: `r${Date.now()}`, runId,
              toolCallId: id, decision: 'APPROVED'
            });
          }
        } catch (_) {}
        continue;
      }
      if (msg.type === 'done') {
        const baseTime = this.lastResponseFinishedAt || this.lastAgentEventAt;
        msg.donePushDelayMs = baseTime ? Date.now() - baseTime : null;
      }
      return msg;
    }
  }
}

function pct(arr, p) {
  if (!arr.length) return null;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1);
  return sorted[idx];
}

function summarize(name, values, unit = 'ms') {
  if (!values.length) return { name, count: 0 };
  const sum = values.reduce((a, b) => a + b, 0);
  return {
    name,
    count: values.length,
    avg: Number((sum / values.length).toFixed(2)),
    p50: Number(pct(values, 50).toFixed(2)),
    p95: Number(pct(values, 95).toFixed(2)),
    min: Number(Math.min(...values).toFixed(2)),
    max: Number(Math.max(...values).toFixed(2)),
    unit
  };
}

async function testProtocol() {
  const results = [];
  const c = new BrowserClient(WS_URL);
  await c.connect();
  // ping
  const pingT = Date.now();
  c.send({ type: 'ping', requestId: 'ping1' });
  const pong = await c.next(m => m.type === 'pong');
  results.push({ name: 'client_ping_server_pong', pass: pong.requestId === 'ping1', latencyMs: Date.now() - pingT });

  // invalid JSON
  c.ws.send('{bad json');
  const errBad = await c.next(m => m.type === 'error');
  results.push({ name: 'invalid_json_returns_error', pass: !!errBad.message });

  // unknown type
  c.send({ type: 'unknown_xyz', requestId: 'u1' });
  const errUnknown = await c.next(m => m.type === 'error');
  results.push({ name: 'unknown_type_returns_error', pass: /未知/.test(errUnknown.message || '') });

  // missing type
  c.send({ requestId: 'mt1', goal: 'x' });
  const errMissing = await c.next(m => m.type === 'error');
  results.push({ name: 'missing_type_returns_error', pass: /type/.test(errMissing.message || '') });

  // simple start_session -> done
  const run = await c.startSession('只回复 OK，不要使用任何工具', '/tmp');
  results.push({ name: 'start_session_success', pass: run.terminal.type === 'done', runId: run.runId, submitMs: run.submitMs, donePushDelayMs: run.terminal.donePushDelayMs ?? null });
  results.push({ name: 'server_done_received', pass: run.terminal.type === 'done' });

  // quick rerun on same runId immediately after done
  const rerun = await c.chat(run.runId, '再回复 OK');
  results.push({ name: 'quick_rerun_after_done', pass: rerun.terminal.type === 'done', donePushDelayMs: rerun.terminal.donePushDelayMs ?? null });

  c.close();
  return results;
}

async function testLatency() {
  const c = new BrowserClient(WS_URL);
  await c.connect();
  const submits = [], e2e = [], pushDone = [];
  const samples = Number(argValue('samples', 10));
  for (let i = 0; i < samples; i++) {
    const t0 = Date.now();
    const run = await c.startSession('只回复 OK，不要使用任何工具', '/tmp');
    submits.push(run.submitMs);
    e2e.push(Date.now() - t0);
    if (run.terminal.donePushDelayMs != null) pushDone.push(run.terminal.donePushDelayMs);
  }
  c.close();
  return {
    samples,
    taskSubmissionOverhead: summarize('task_submission_overhead_to_session_started', submits),
    agentRoundtrip: summarize('agent_roundtrip_start_to_done', e2e),
    completionPushDelay: summarize('response_finished_to_done_push', pushDone)
  };
}

async function testConcurrency(n = 10) {
  const started = Date.now();
  const clients = [];
  const tasks = [];
  for (let i = 0; i < n; i++) {
    const c = new BrowserClient(WS_URL);
    clients.push(c);
    tasks.push(c.connect().then(() =>
      c.startSession('只回复 OK，不要使用任何工具', `/tmp/conc-${i}`)
    ));
  }
  const runs = await Promise.allSettled(tasks);
  for (const c of clients) c.close();
  const okRuns = runs.filter(r => r.status === 'fulfilled' && r.value.terminal.type === 'done');
  const ok = okRuns.length;
  const totalMs = Date.now() - started;
  const submits = okRuns.map(r => r.value.submitMs);
  const pushDelays = okRuns.map(r => r.value.terminal.donePushDelayMs).filter(v => v != null);
  return {
    concurrentSessions: n,
    success: ok,
    successRate: Number((ok / n * 100).toFixed(2)),
    totalTimeMs: totalMs,
    throughputPerMinute: Number((ok / totalMs * 60000).toFixed(2)),
    taskSubmissionOverhead: summarize('concurrent_task_submission_overhead', submits),
    completionPushDelay: summarize('concurrent_response_finished_to_done_push', pushDelays)
  };
}

async function testStopInterrupt() {
  const c = new BrowserClient(WS_URL);
  await c.connect();
  const res = {};
  // Stop during a running/simple task
  const t0 = Date.now();
  c.send({ type: 'start_session', requestId: 'stopStart', goal: '请详细解释量子计算，尽量多写一些内容，不要使用工具', workspace: '/tmp' });
  const started = await c.next(m => m.type === 'session_started');
  const stop = await c.stop(started.runId);
  res.stopLatencyMs = stop.latencyMs;
  res.stopPass = stop.msg.type === 'stopped';

  // The session should be free again.
  const afterStop = await c.chat(started.runId, '现在回复 OK');
  res.stopThenRerunPass = afterStop.terminal.type === 'done';

  // Interrupt during a running task.
  c.send({ type: 'start_session', requestId: 'intStart', goal: '请详细解释人工智能，尽量多写一些内容，不要使用工具', workspace: '/tmp' });
  const iStarted = await c.next(m => m.type === 'session_started');
  const intr = await c.interrupt(iStarted.runId);
  res.interruptLatencyMs = intr.latencyMs;
  res.interruptPass = intr.msg.type === 'interrupted';
  c.close();
  return res;
}

async function runApproval(command, decision = 'APPROVED', editedArgs = null) {
  const c = new BrowserClient(WS_URL);
  await c.connect();
  let requested = null;
  const goal = `请调用 shell 工具执行命令：${command}，执行完成后直接回复完成，不要做其他事情`;
  const run = await c.startSession(goal, '/tmp', async (client, runId, msg) => {
    requested = msg;
    const payload = {
      type: 'permission_respond', requestId: `r${Date.now()}`, runId,
      toolCallId: msg.toolCallId, toolName: msg.toolName, decision, arguments: editedArgs || msg.arguments
    };
    client.send(payload);
  });
  c.close();
  return { command, decision, requested: !!requested, terminal: run.terminal.type, runId: run.runId };
}

async function testApproval() {
  const results = [];
  // safe command should auto-approve
  console.error('[approval] safe...');
  const safe = await runApproval('echo hi');
  results.push({ name: 'safe_command_auto_approve_no_permission', pass: safe.terminal === 'done' && !safe.requested, command: safe.command });

  // outside cwd should request permission
  console.error('[approval] outside approve...');
  const outside = await runApproval('cat /etc/hosts', 'APPROVED');
  results.push({ name: 'outside_cwd_approval_requested', pass: outside.requested && outside.terminal === 'done', command: outside.command });

  // REJECTED should still complete without executing
  console.error('[approval] rejected...');
  const rejected = await runApproval('cat /etc/hosts', 'REJECTED');
  results.push({ name: 'rejected_approval_no_crash', pass: rejected.requested && rejected.terminal === 'done', command: rejected.command });

  // EDITED 暂时关闭，但前端仍可提交 EDITED；这里验证它不会再次进入审批循环。
  console.error('[approval] edited disabled...');
  const edited = await runApproval('cat /etc/hosts', 'EDITED', JSON.stringify({ command: 'echo edited' }));
  results.push({ name: 'edited_approval_disabled_no_loop', pass: edited.terminal === 'done' && edited.requested, command: edited.command });

  // APPROVE_ALL: first request, then second same command should not ask again
  console.error('[approval] approve_all...');
  const c2 = new BrowserClient(WS_URL);
  await c2.connect();
  let firstAsk = false, secondAsk = false;
  const r1 = await c2.startSession('请调用 shell 工具执行命令：cat /etc/hosts，执行完成后直接回复完成，不要做其他事情', '/tmp', async (client, runId, msg) => {
    firstAsk = true;
    client.send({ type: 'permission_respond', requestId: `r${Date.now()}`, runId,
      toolCallId: msg.toolCallId, toolName: msg.toolName, decision: 'APPROVE_ALL', arguments: msg.arguments });
  });
  const r2 = await c2.chat(r1.runId, '再次调用 shell 工具执行命令：cat /etc/hosts，执行完成后直接回复完成', async (client, runId, msg) => {
    secondAsk = true;
    client.send({ type: 'permission_respond', requestId: `r${Date.now()}`, runId,
      toolCallId: msg.toolCallId, toolName: msg.toolName, decision: 'APPROVED', arguments: msg.arguments });
  });
  c2.close();
  results.push({ name: 'approve_all_second_no_ask', pass: r1.terminal.type === 'done' && r2.terminal.type === 'done' && firstAsk && !secondAsk,
    firstAsk, secondAsk, firstTerminal: r1.terminal.type, secondTerminal: r2.terminal.type });

  // Dangerous command cannot be bypassed by approve_all (use rm -rf /tmp/agentcode-test-file)
  console.error('[approval] dangerous_no_bypass...');
  const c3 = new BrowserClient(WS_URL);
  await c3.connect();
  let dangerAsk = false;
  const danger = await c3.startSession('请调用 shell 工具执行命令：rm -rf /tmp/agentcode-test-file，执行完成后直接回复完成', '/tmp', async (client, runId, msg) => {
    dangerAsk = true;
    client.send({ type: 'permission_respond', requestId: `r${Date.now()}`, runId,
      toolCallId: msg.toolCallId, toolName: msg.toolName, decision: 'APPROVE_ALL', arguments: msg.arguments });
  });
  let dangerSecondAsk = false;
  const danger2 = await c3.chat(danger.runId, '再次调用 shell 工具执行命令：rm -rf /tmp/agentcode-test-file，执行完成后直接回复完成', async (client, runId, msg) => {
    dangerSecondAsk = true;
    client.send({ type: 'permission_respond', requestId: `r${Date.now()}`, runId,
      toolCallId: msg.toolCallId, toolName: msg.toolName, decision: 'APPROVED', arguments: msg.arguments });
  });
  c3.close();
  results.push({ name: 'dangerous_not_bypassed_by_approve_all', pass: danger.terminal.type === 'done' && dangerAsk && danger2.terminal.type === 'done' && dangerSecondAsk,
    dangerAsk, dangerSecondAsk, firstTerminal: danger.terminal.type, secondTerminal: danger2.terminal.type });
  return results;
}

async function testApprovalTimeout() {
  const c = new BrowserClient(WS_URL);
  await c.connect();
  const runId = await new Promise((resolve, reject) => {
    c.send({ type: 'start_session', requestId: 'timeout', goal: '请调用 shell 工具执行命令：cat /etc/hosts，不要做其他事情', workspace: '/tmp' });
    c.next(m => m.type === 'session_started').then(m => resolve(m.runId)).catch(reject);
  });
  // wait for permission request
  await c.next(m => m.type === 'permission_requested');
  // Wait longer than approval-wait-timeout plus one maintenance interval.
  await new Promise(r => setTimeout(r, 8500));
  const after = await c.chat(runId, '现在回复 OK');
  c.close();
  return { timeoutThenRerunPass: after.terminal.type === 'done' };
}

async function main() {
  const report = { ws: WS_URL, startedAt: new Date().toISOString(), results: {} };

  if (args.includes('--approval-only')) {
    try {
      report.approval = await testApproval();
    } catch (e) {
      report.approvalError = e.message;
    }
    try {
      report.approvalTimeout = await testApprovalTimeout();
    } catch (e) {
      report.approvalTimeoutError = e.message;
    }
    report.finishedAt = new Date().toISOString();
    console.log(JSON.stringify(report, null, 2));
    process.exit(0);
  }

  report.protocol = await testProtocol();
  report.latency = await testLatency();
  report.concurrency = await testConcurrency(Number(argValue('concurrency', 10)));
  report.stopInterrupt = await testStopInterrupt();

  // Approval tests can be slow; run only if not skipped.
  if (!args.includes('--skip-approval')) {
    try {
      report.approval = await testApproval();
    } catch (e) {
      report.approvalError = e.message;
    }
    try {
      report.approvalTimeout = await testApprovalTimeout();
    } catch (e) {
      report.approvalTimeoutError = e.message;
    }
  }

  report.finishedAt = new Date().toISOString();
  console.log(JSON.stringify(report, null, 2));
  process.exit(0);
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});