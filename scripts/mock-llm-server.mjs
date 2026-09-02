#!/usr/bin/env node
// Browser-simulation support: minimal OpenAI-compatible LLM stub.
// It is intentionally deterministic so WebSocket/ReAct/approval flows can be
// exercised without a real model. It emits shell tool calls for markers like:
//   @@SHELL:echo hi@@
//   @@MULTI_SHELL:echo one||echo two@@
import http from 'node:http';
import { URL } from 'node:url';

const PORT = Number(process.env.MOCK_LLM_PORT || 19000);
const host = '127.0.0.1';

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function sendSse(res, chunks) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  for (const chunk of chunks) {
    res.write(`data: ${JSON.stringify(chunk)}\n\n`);
  }
  res.write('data: [DONE]\n\n');
  res.end();
}

function firstUserText(messages) {
  for (const m of messages || []) {
    if (m.role === 'user' && typeof m.content === 'string') return m.content;
    if (m.role === 'user' && Array.isArray(m.content)) {
      return m.content.map(x => x.text || '').join('');
    }
  }
  return '';
}

function hasToolResult(messages) {
  // 只看最后一条 user 消息之后是否出现 tool 结果：
  // 多轮会话的历史里会携带上一轮的 tool 消息，若全局判断，
  // 第二轮对话将永远不再发起工具调用（审批 APPROVE_ALL 等场景无法覆盖）。
  const msgs = messages || [];
  let lastUser = -1;
  for (let i = 0; i < msgs.length; i++) {
    if (msgs[i] && msgs[i].role === 'user') lastUser = i;
  }
  return msgs.slice(lastUser + 1).some(m => m.role === 'tool');
}

function parseMarker(text) {
  if (!text) return null;
  if (text.includes('@@ECHO_CONTEXT@@')) return { kind: 'echo_context' };
  let m = text.match(/@@APPEND_NOTE:([^@]+)@@/);
  if (m) return { kind: 'append_note', content: m[1] };
  m = text.match(/@@UPDATE_NOTE:([^@]+)@@/);
  if (m) return { kind: 'update_note', content: m[1] };
  m = text.match(/@@MULTI_SHELL:([^@]+)@@/);
  if (m) {
    const commands = m[1].split('||').map(s => s.trim()).filter(Boolean);
    return { kind: 'multi_shell', commands };
  }
  m = text.match(/@@SHELL:([^@]+)@@/);
  if (m) return { kind: 'shell', command: m[1].trim() };
  m = text.match(/@@WRITE:([^@]+)@@:([^@]+)@@/);
  if (m) return { kind: 'write', path: m[1].trim(), content: m[2] };
  return null;
}

function sleepMs(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// 确定性 embedding：词法哈希（latin 词 + CJK bigram/单字），L2 归一化。
// 不是语义向量，但 lexical 重叠越多 cosine 越高，足以驱动 kNN 召回链路。
function deterministicEmbedding(text, dims) {
  const vec = new Array(dims).fill(0);
  const tokens = [];
  const lower = text.toLowerCase();
  for (const m of lower.matchAll(/[a-z0-9]+/g)) tokens.push(m[0]);
  for (const m of lower.matchAll(/[\u4e00-\u9fff]/g)) tokens.push(m[0]); // CJK 单字
  for (const m of lower.matchAll(/[\u4e00-\u9fff]{2}/g)) tokens.push(m[0]); // CJK bigram
  if (tokens.length === 0) tokens.push('__empty__');
  for (const token of tokens) {
    let h = 2166136261;
    for (let i = 0; i < token.length; i++) {
      h ^= token.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    vec[Math.abs(h) % dims] += 1 / Math.sqrt(tokens.length);
  }
  let norm = 0;
  for (const v of vec) norm += v * v;
  norm = Math.sqrt(norm) || 1;
  return vec.map(v => v / norm);
}

function systemPromptText(messages) {
  for (const m of messages || []) {
    if (m.role === 'system' && typeof m.content === 'string') return m.content;
  }
  return '';
}

function assistantChunk(text) {
  return {
    id: 'chatcmpl-mock',
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: 'mock',
    choices: [{ index: 0, delta: { role: 'assistant', content: text }, finish_reason: null }]
  };
}

function finishChunk() {
  return {
    id: 'chatcmpl-mock',
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: 'mock',
    choices: [{ index: 0, delta: {}, finish_reason: 'stop' }]
  };
}

function toolCallMessage(id, name, args) {
  return {
    id: 'chatcmpl-mock',
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: 'mock',
    choices: [{
      index: 0,
      message: {
        role: 'assistant',
        content: null,
        tool_calls: [{
          id,
          type: 'function',
          function: { name, arguments: typeof args === 'string' ? args : JSON.stringify(args) }
        }]
      },
      finish_reason: 'tool_calls'
    }],
    usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 }
  };
}

function toolCallStreamChunk(id, name, args) {
  return {
    id: 'chatcmpl-mock',
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: 'mock',
    choices: [{
      index: 0,
      delta: {
        role: 'assistant',
        tool_calls: [{
          index: 0,
          id,
          type: 'function',
          function: { name, arguments: typeof args === 'string' ? args : JSON.stringify(args) }
        }]
      },
      finish_reason: 'tool_calls'
    }]
  };
}

function finalMessageText(messages) {
  // If a marker contains a simple reply target, use it.
  const text = firstUserText(messages);
  const m = text && text.match(/@@REPLY:([^@]+)@@/);
  return m ? m[1] : 'OK';
}

// ---- 长期记忆抽取的故障注入：验证 HybridMemoryStore.extractMemory 的重试与降级 ----
// MOCK_EXTRACT_FAIL_TIMES=N : 前 N 次"抽取请求"返回上游异常文本，复现真实模型超时后
//                             图节点把错误文本当回答返回的场景（无 JSON 负载）。
// MOCK_EXTRACT_MEMO         : 恢复后返回的记忆正文，用于断言重试成功的那条记忆真的落库。
let extractAttempts = 0;
const MEMORY_PROMPT_MARKER = '长期记忆抽取与分类器';

function memoryExtractResponse() {
  extractAttempts += 1;
  const failTimes = Number(process.env.MOCK_EXTRACT_FAIL_TIMES || 0);
  // 未显式开启注入时保持原有行为（回 "OK"，抽取解析不到 JSON -> 无可记忆内容），
  // 避免这个诊断开关改变既有 mock 回归模式的语义。
  if (failTimes <= 0 && !process.env.MOCK_EXTRACT_MEMO) {
    return { kind: 'final', text: 'OK' };
  }
  if (extractAttempts <= failTimes) {
    return { kind: 'final', text: `Exception: upstream LLM timeout (injected ${extractAttempts}/${failTimes})` };
  }
  const memo = process.env.MOCK_EXTRACT_MEMO || '用户喜欢喝加双份糖的美式咖啡。';
  return {
    kind: 'final',
    text: JSON.stringify({
      memories: [{ action: 'ADD', type: 'USER', scope: 'cross_session', content: memo,
        confidence: 0.9, importance: 0.9, tags: ['injected'] }]
    })
  };
}

function buildResponse(messages) {
  if (JSON.stringify(messages).includes(MEMORY_PROMPT_MARKER)) {
    return memoryExtractResponse();
  }
  const text = firstUserText(messages);
  const marker = parseMarker(text);
  // If we already performed the tool call (a tool result is present), stop.
  if (hasToolResult(messages)) {
    return { kind: 'final', text: finalMessageText(messages) };
  }
  if (marker && marker.kind === 'shell') {
    return { kind: 'tool', name: 'shell', args: { command: marker.command } };
  }
  if (marker && marker.kind === 'multi_shell') {
    return {
      kind: 'tool_multi',
      calls: marker.commands.map((command, i) => ({
        id: `call_multi_${i}`,
        name: 'shell',
        args: { command }
      }))
    };
  }
  if (marker && marker.kind === 'write') {
    return { kind: 'tool', name: 'write_file', args: { file_path: marker.path, content: marker.content } };
  }
  return { kind: 'final', text: finalMessageText(messages) };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'GET' && url.pathname === '/health') {
    sendJson(res, 200, { status: 'UP' });
    return;
  }
  if (req.method === 'GET' && url.pathname.endsWith('/models')) {
    sendJson(res, 200, { object: 'list', data: [{ id: 'mock', object: 'model', owned_by: 'mock' }] });
    return;
  }
  if (req.method === 'POST' && url.pathname.endsWith('/embeddings')) {
    let eraw = '';
    req.on('data', c => { eraw += c; });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(eraw); } catch { sendJson(res, 400, { error: { message: 'bad json' } }); return; }
      const inputs = Array.isArray(body.input) ? body.input : [body.input];
      const dims = Number(body.dimensions) || Number(process.env.MOCK_EMBEDDING_DIMS || 1024);
      const data = inputs.map((text, index) => ({
        object: 'embedding',
        embedding: deterministicEmbedding(String(text ?? ''), dims),
        index
      }));
      sendJson(res, 200, {
        object: 'list',
        data,
        model: body.model || 'mock-embedding',
        usage: { prompt_tokens: 1, total_tokens: 1 }
      });
    });
    return;
  }
  if (req.method !== 'POST' || !url.pathname.endsWith('/chat/completions')) {
    sendJson(res, 404, { error: { message: 'not found' } });
    return;
  }
  let raw = '';
  req.on('data', chunk => { raw += chunk; });
  req.on('end', () => {
    let body;
    try {
      body = JSON.parse(raw);
    } catch (e) {
      sendJson(res, 400, { error: { message: 'bad json' } });
      return;
    }
    const messages = body.messages || [];
    const resp = buildResponse(messages);
    if (body.stream) {
      if (resp.kind === 'final') {
        sendSse(res, [assistantChunk(resp.text), finishChunk()]);
      } else if (resp.kind === 'tool') {
        sendSse(res, [toolCallStreamChunk('call_1', resp.name, resp.args), finishChunk()]);
      } else if (resp.kind === 'tool_multi') {
        const chunks = resp.calls.map(call => toolCallStreamChunk(call.id, call.name, call.args));
        sendSse(res, [...chunks, finishChunk()]);
      } else {
        sendSse(res, [assistantChunk('unhandled'), finishChunk()]);
      }
      return;
    }
    if (resp.kind === 'final') {
      sendJson(res, 200, {
        id: 'chatcmpl-mock',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'mock',
        choices: [{ index: 0, message: { role: 'assistant', content: resp.text }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 }
      });
    } else if (resp.kind === 'tool') {
      sendJson(res, 200, toolCallMessage('call_1', resp.name, resp.args));
    } else if (resp.kind === 'tool_multi') {
      sendJson(res, 200, {
        id: 'chatcmpl-mock',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'mock',
        choices: [{
          index: 0,
          message: {
            role: 'assistant',
            content: null,
            tool_calls: resp.calls.map((call, i) => ({
              id: call.id,
              type: 'function',
              function: { name: call.name, arguments: JSON.stringify(call.args) }
            }))
          },
          finish_reason: 'tool_calls'
        }],
        usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 }
      });
    }
  });
});

server.listen(PORT, host, () => {
  console.log(`mock llm listening on http://${host}:${PORT}`);
});