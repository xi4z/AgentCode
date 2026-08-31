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
  return (messages || []).some(m => m.role === 'tool');
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

function buildResponse(messages) {
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