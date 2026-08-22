#!/usr/bin/env node
// AgentCode Java 终端对话客户端
// 用法: node scripts/terminal-chat.mjs [--ws ws://localhost:8080/ws/chat] [--workspace /tmp] [--goal "目标"]

import process from 'node:process';
import readline from 'node:readline/promises';

const args = process.argv.slice(2);
function argValue(name, fallback) {
    const idx = args.indexOf('--' + name);
    return idx >= 0 && args[idx + 1] ? args[idx + 1] : fallback;
}

const WS_URL = argValue('ws', 'ws://localhost:8080/ws/chat');
const DEFAULT_WORKSPACE = argValue('workspace', process.cwd());
const DEFAULT_GOAL = argValue('goal', '');

let ws;
let runId = null;
let busy = false;
let turnResolve = null;
let streamKind = null;

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: true
});

function ask(question) {
    return rl.question(question).then((answer) => answer.trim());
}

function log(message) {
    process.stdout.write(message + '\n');
}

function streamPrefix(kind) {
    return kind === 'thinking' ? '  💭 ' : '  🤖 ';
}

function finishStreamLine() {
    if (streamKind) {
        process.stdout.write('\n');
        streamKind = null;
    }
}

function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    } else {
        log('WebSocket 未连接，无法发送消息');
    }
}

function nextRequestId() {
    return 'r' + Date.now() + '-' + Math.random().toString(16).slice(2, 8);
}

function waitForTurnEnd() {
    return new Promise((resolve) => {
        turnResolve = resolve;
    });
}

function finishTurn() {
    if (turnResolve) {
        const resolve = turnResolve;
        turnResolve = null;
        resolve();
    }
}

async function handlePermission(msg) {
    log('\n🔐 收到工具审批请求:');
    log('  toolCallId: ' + msg.toolCallId);
    log('  toolName:   ' + msg.toolName);
    log('  arguments:  ' + (msg.arguments || ''));
    log('  description:' + (msg.description || ''));
    const answer = await ask('请选择 [a] 批准, [all] 本会话全部批准, [n] 拒绝 > ');
    const lower = answer.toLowerCase();
    let decision;
    if (lower === 'a' || lower === 'approve' || lower === 'y') {
        decision = 'APPROVED';
    } else if (lower === 'all' || lower === 'approve_all') {
        decision = 'APPROVE_ALL';
    } else {
        decision = 'REJECTED';
    }
    send({
        type: 'permission_respond',
        requestId: nextRequestId(),
        runId,
        toolCallId: msg.toolCallId,
        toolName: msg.toolName,
        decision,
        arguments: msg.arguments
    });
    log('已发送审批决定: ' + decision + '\n');
}

async function connect() {
    return new Promise((resolve, reject) => {
        ws = new WebSocket(WS_URL);
        ws.onopen = () => {
            log('✅ 已连接 WebSocket: ' + WS_URL + '\n');
            resolve();
        };
        ws.onerror = (e) => {
            log('❌ WebSocket 错误');
            reject(e);
        };
        ws.onclose = () => {
            log('❌ WebSocket 连接已关闭');
            if (busy) {
                busy = false;
                finishTurn();
            }
        };
        ws.onmessage = async (event) => {
            let msg;
            try {
                msg = JSON.parse(event.data);
            } catch (e) {
                log('无法解析消息: ' + event.data);
                return;
            }
            await handleMessage(msg);
        };
    });
}

async function handleMessage(msg) {
    switch (msg.type) {
        case 'session_started':
            runId = msg.runId;
            log('📌 会话已创建: ' + runId);
            break;

        case 'agent_event':
            printAgentEvent(msg.status, msg.content);
            break;

        case 'permission_requested':
            finishStreamLine();
            await handlePermission(msg);
            break;

        case 'done':
            finishStreamLine();
            log('✅ 本轮完成\n');
            busy = false;
            finishTurn();
            break;

        case 'error':
            finishStreamLine();
            log('❌ 错误: ' + msg.message);
            busy = false;
            finishTurn();
            break;

        case 'stopped':
            finishStreamLine();
            log('⏹ 已停止');
            busy = false;
            finishTurn();
            break;

        case 'interrupted':
            finishStreamLine();
            log('⏸ 已中断: ' + (msg.message || ''));
            busy = false;
            finishTurn();
            break;

        default:
            log('📨 未知消息: ' + JSON.stringify(msg));
    }
}

function printAgentEvent(status, content) {
    if (status === 'PERMISSION_REQUESTED') return;
    if (status === 'TOOL_FINISHED') {
        finishStreamLine();
        log('  🔧 [工具执行完成]');
    } else if (status === 'TOOL_STREAMING') {
        finishStreamLine();
        log('  🔧 调用工具: ' + (content || ''));
    } else if (status === 'THINKING_STREAMING' || status === 'RESPONSE_STREAMING') {
        const kind = status === 'THINKING_STREAMING' ? 'thinking' : 'response';
        if (streamKind !== kind) {
            finishStreamLine();
            streamKind = kind;
            process.stdout.write(streamPrefix(kind));
        }
        process.stdout.write(content || '');
    } else if (status === 'THINKING_FINISHED') {
        if (streamKind === 'thinking') {
            if (content) process.stdout.write(content);
            finishStreamLine();
        } else {
            log('  💭 ' + (content || ''));
        }
    } else if (status === 'RESPONSE_FINISHED') {
        if (streamKind === 'response') {
            if (content) process.stdout.write(content);
            finishStreamLine();
        } else {
            log('  🤖 ' + (content || ''));
        }
    } else {
        finishStreamLine();
        log('  ' + status + ': ' + (content || ''));
    }
}

async function startSession(goal, workspace) {
    if (busy) {
        log('当前有任务正在执行，请先等待完成或使用 stop/interrupt');
        return;
    }
    busy = true;
    send({
        type: 'start_session',
        requestId: nextRequestId(),
        goal,
        workspace
    });
    log('▶ 已发起新会话，等待服务端处理...');
    await waitForTurnEnd();
}

async function chatToSession(content) {
    if (!runId) {
        log('还没有 runId，请先 start');
        return;
    }
    if (busy) {
        log('当前有任务正在执行，请先等待完成或使用 stop/interrupt');
        return;
    }
    busy = true;
    send({
        type: 'chat',
        requestId: nextRequestId(),
        runId,
        content
    });
    log('▶ 已发送多轮消息，等待响应...');
    await waitForTurnEnd();
}

async function main() {
    await connect();

    if (DEFAULT_GOAL) {
        const workspace = DEFAULT_WORKSPACE;
        log('使用默认工作区: ' + workspace);
        await startSession(DEFAULT_GOAL, workspace);
    } else {
        const workspace = await ask('工作区路径 (默认: ' + DEFAULT_WORKSPACE + ')> ') || DEFAULT_WORKSPACE;
        const goal = await ask('初始目标 > ');
        if (!goal) {
            log('未输入目标，退出');
            rl.close();
            process.exit(0);
        }
        await startSession(goal, workspace);
    }

    while (true) {
        const input = await ask('多轮对话 (输入 exit 退出, help 查看命令) > ');
        if (!input) continue;
        if (input.trim().toLowerCase() === 'exit' || input.trim().toLowerCase() === 'quit') {
            log('再见');
            rl.close();
            process.exit(0);
        }
        if (input.trim().toLowerCase() === 'help') {
            log('可用命令: exit, help, stop, interrupt <guidance>, 其他内容将作为下一条消息发送');
            continue;
        }
        if (input.trim().toLowerCase() === 'stop') {
            send({ type: 'stop', requestId: nextRequestId(), runId });
            log('已请求停止');
            continue;
        }
        if (input.trim().toLowerCase().startsWith('interrupt')) {
            const guidance = input.trim().slice('interrupt'.length).trim() || '请停止当前操作';
            send({ type: 'interrupt', requestId: nextRequestId(), runId, guidance });
            log('已请求中断');
            continue;
        }
        await chatToSession(input);
    }
}

main().catch((err) => {
    console.error(err);
    rl.close();
    process.exit(1);
});
