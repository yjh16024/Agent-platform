#!/usr/bin/env node
/*
 * 极简 MCP stdio server —— **仅供平台测试使用**。
 *
 * 它存在的意义：stdio 传输的正确性（换行分隔、请求-响应配对、握手顺序、关流退出）
 * 只有对着一个真实的子进程才能验证。若用 mock，就只是"验证了我们的假设"，
 * 而假设错了的代价正是这类代码最容易出的问题（响应交错时按序读会全错位）。
 *
 * 按 MCP 规范 2025-06-18：
 *   - 消息为换行分隔的 UTF-8 JSON-RPC，不得含内嵌换行；
 *   - stdout 只写协议消息（日志绝不能进 stdout）；
 *   - initialize 必须是第一次交互；收到 notifications/initialized 后才算就绪；
 *   - stdin 关闭即退出（客户端 close() 的第一步就是关输入流）。
 */

const readline = require('readline');

const rl = readline.createInterface({ input: process.stdin, terminal: false });

function send(obj) {
  // 每条消息一行：这是协议要求（换行分隔），不能美化输出
  process.stdout.write(JSON.stringify(obj) + '\n');
}

rl.on('line', (line) => {
  const t = line.trim();
  if (!t) return;

  let msg;
  try {
    msg = JSON.parse(t);
  } catch (e) {
    // 非协议内容直接忽略（真实 server 也不该往 stdout 写日志）
    return;
  }

  const { id, method, params } = msg;

  // 通知（无 id）：不回应
  if (id === undefined || id === null) {
    return;
  }

  switch (method) {
    case 'initialize':
      send({
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: '2025-06-18',
          capabilities: { tools: {} },
          serverInfo: { name: 'fake-mcp-server', version: '1.0.0' },
        },
      });
      break;

    case 'tools/list':
      send({
        jsonrpc: '2.0',
        id,
        result: {
          tools: [
            {
              name: 'echo',
              description: '回显输入文本',
              inputSchema: {
                type: 'object',
                properties: { text: { type: 'string' } },
                required: ['text'],
              },
            },
            {
              name: 'boom',
              description: '总是失败（用于验证 isError 处理）',
              inputSchema: { type: 'object', properties: {} },
            },
          ],
        },
      });
      break;

    case 'tools/call': {
      const name = params && params.name;
      if (name === 'boom') {
        send({
          jsonrpc: '2.0',
          id,
          result: { content: [{ type: 'text', text: '故意失败' }], isError: true },
        });
      } else {
        const args = (params && params.arguments) || {};
        send({
          jsonrpc: '2.0',
          id,
          result: { content: [{ type: 'text', text: 'echo:' + (args.text == null ? '' : args.text) }] },
        });
      }
      break;
    }

    default:
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'method not found: ' + method } });
  }
});

// 客户端 close() 的规范流程是"先关输入流"—— 这里必须真的退出，否则会被 SIGTERM 强杀
rl.on('close', () => process.exit(0));
