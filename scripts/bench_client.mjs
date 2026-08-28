#!/usr/bin/env node
// AgentCode Java 真实 LLM 评测客户端（非交互）
// 用途：保持默认审批配置（shell/write_file/edit_file 需审批），
//      对每个 goal 自动答复 APPROVED，跑固定评测集以采集 p50/p95 与 tokens_per_run 基线。
//
// 用法:
//   node scripts/bench_client.mjs --ws ws://localhost:18080/ws/chat \
//     --workspace /abs/path [--suite suite.json] [--repeat 20] \
//     [--goal "..."] [--decision APPROVED] [--timeout-ms 120000]
//
// suite.json: 字符串数组，每个元素是一个 goal。
// 输出：stdout 每行一个 JSON：{"index","runId","status","clientMs","approvals","error"}
//       末尾打印 {"summary": {...}} 汇总成功/失败数与客户端墙钟。
// 注意：Token 由服务端 AUDIT_AI_STREAM 记录，脚本不直接读取，交给 collect_metrics.py 关联。

import process from 'node:process';
import { readFileSync } from 'node:fs';

const args = process.argv.slice(2);
function argValue(name, fallback) {
    const idx = args.indexOf('--' + name);
    return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}
function hasFlag(name) {
    return args.includes('--' + name);
}

const WS_URL = argValue('ws', 'ws://localhost:18080/ws/chat');
const WORKSPACE = argValue('workspace', process.cwd());
const SUITE_FILE = argValue('suite', null);
const SINGLE_GOAL = argValue('goal', null);
const REPEAT = Number(argValue('repeat', '1'));
const DECISION = argValue('decision', 'APPROVED');
const TIMEOUT_MS = Number(argValue('timeout-ms', '120000'));
const GAP_MS = Number(argValue('gap-ms', '1500')); // 顺序运行的间隔，避免并发窗口串扰 token 关联

function buildGoals() {
    let goals = [];
    if (SUITE_FILE) {
        goals = JSON.parse(readFileSync(SUITE_FILE, 'utf-8'));
        if (!Array.isArray(goals) || goals.length === 0) {
            throw new Error('suite 文件必须是非空字符串数组');
        }
    } else if (SINGLE_GOAL) {
        goals = [SINGLE_GOAL];
    } else {
        throw new Error('需要 --suite 或 --goal');
    }
    const out = [];
    for (let r = 0; r < Math.max(1, REPEAT); r++) {
        for (const g of goals) out.push(g);
    }
    return out;
}

function sleep(ms) {
    return new Promise((res) => setTimeout(res, ms));
}

function nextRequestId() {
    return 'b' + Date.now() + '-' + Math.random().toString(16).slice(2, 8);
}

// 跑单个 goal：一次连接，自动审批，done/error/stopped 结束
function runGoal(goal, index) {
    return new Promise((resolve) => {
        const t0 = Date.now();
        let runId = null;
        let approvals = 0;
        let settled = false;
        let timer = null;
        let ws;

        function finish(status, error) {
            if (settled) return;
            settled = true;
            if (timer) clearTimeout(timer);
            try { if (ws && ws.readyState === WebSocket.OPEN) ws.close(); } catch { /* ignore */ }
            resolve({
                index,
                runId,
                status,
                clientMs: Date.now() - t0,
                approvals,
                error: error || null,
            });
        }

        timer = setTimeout(() => finish('timeout', 'client timeout'), TIMEOUT_MS);

        try {
            ws = new WebSocket(WS_URL);
        } catch (e) {
            finish('connect_error', String(e && e.message ? e.message : e));
            return;
        }

        ws.onopen = () => {
            ws.send(JSON.stringify({
                type: 'start_session',
                requestId: nextRequestId(),
                goal,
                workspace: WORKSPACE,
            }));
        };
        ws.onerror = () => finish('ws_error', 'websocket error');
        ws.onclose = () => { if (!settled) finish('closed', 'connection closed before done'); };
        ws.onmessage = (event) => {
            let msg;
            try { msg = JSON.parse(event.data); } catch { return; }
            switch (msg.type) {
                case 'session_started':
                    runId = msg.runId;
                    break;
                case 'permission_requested':
                    approvals++;
                    ws.send(JSON.stringify({
                        type: 'permission_respond',
                        requestId: nextRequestId(),
                        runId: msg.runId || runId,
                        toolCallId: msg.toolCallId,
                        toolName: msg.toolName,
                        decision: DECISION,
                        arguments: msg.arguments,
                    }));
                    break;
                case 'permission_pending':
                    // 已记录决定，仍等其它审批项；不结束本轮
                    break;
                case 'done':
                    finish('completed', null);
                    break;
                case 'error':
                    finish('error', msg.message);
                    break;
                case 'stopped':
                    finish('stopped', null);
                    break;
                default:
                    break;
            }
        };
    });
}

async function main() {
    const goals = buildGoals();
    const results = [];
    for (let i = 0; i < goals.length; i++) {
        const r = await runGoal(goals[i], i);
        results.push(r);
        process.stdout.write(JSON.stringify(r) + '\n');
        if (i < goals.length - 1 && GAP_MS > 0) await sleep(GAP_MS);
    }
    const ok = results.filter((r) => r.status === 'completed').length;
    const failed = results.length - ok;
    const totalApprovals = results.reduce((a, r) => a + r.approvals, 0);
    process.stdout.write(JSON.stringify({
        summary: {
            total: results.length,
            completed: ok,
            failed,
            totalApprovals,
            avgClientMs: results.length ? Math.round(results.reduce((a, r) => a + r.clientMs, 0) / results.length) : 0,
        },
    }) + '\n');
    if (failed > 0) process.exitCode = 2;
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
