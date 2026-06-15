// dashboard 纯函数单元测试，覆盖《项目测试计划与清单》§4.5。
// 使用 Node 内置 node:test，零依赖。运行：node --test dashboard/
//
// 关键点：require('../server.js') 不会启动 HTTP 服务，因为 server.js 内部用
// `require.main === module` 守卫了 server.listen 与信号注册（仅直接运行时才监听）。

const { test } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const { classifySudoFailure, isPortListening } = require(path.join(__dirname, '..', 'server.js'));

// ---------------------------------------------------------------------------
// TC-DASH-001 [P1] classifySudoFailure()：各类 sudo 错误正确分类
// ---------------------------------------------------------------------------

test('TC-DASH-001 sudo 被环境阻断 → sudo-blocked', () => {
  const samples = [
    'operation not permitted',
    'spawn sudo EPERM',
    'sudo: command not found',
    'no such file or directory',
    'cannot execute binary file',
  ];
  for (const s of samples) {
    assert.strictEqual(classifySudoFailure(s), 'sudo-blocked', `应判为 sudo-blocked: ${s}`);
  }
});

test('TC-DASH-001 需要一次性免密配置 → needs-setup', () => {
  const samples = [
    'sudo: a password is required',
    'a password is required',
    'sudo: a terminal is required to read the password',
    'sudo: no askpass program specified',
    'sudo: some other complaint', // 裸 "sudo:" 前缀
  ];
  for (const s of samples) {
    assert.strictEqual(classifySudoFailure(s), 'needs-setup', `应判为 needs-setup: ${s}`);
  }
});

test('TC-DASH-001 其他错误 → other', () => {
  assert.strictEqual(classifySudoFailure('connection refused by remote'), 'other');
  assert.strictEqual(classifySudoFailure('disk full'), 'other');
});

test('TC-DASH-001 空/undefined/null 输入不崩溃且归为 other', () => {
  assert.strictEqual(classifySudoFailure(''), 'other');
  assert.strictEqual(classifySudoFailure(undefined), 'other');
  assert.strictEqual(classifySudoFailure(null), 'other');
});

test('TC-DASH-001 大小写不敏感', () => {
  assert.strictEqual(classifySudoFailure('OPERATION NOT PERMITTED'), 'sudo-blocked');
  assert.strictEqual(classifySudoFailure('A PASSWORD IS REQUIRED'), 'needs-setup');
});

test('TC-DASH-001 "blocked" 优先于 "needs-setup"（避免误判 sudoers 配置问题）', () => {
  // 同时含 "operation not permitted"(blocked) 与 "password is required"(setup)
  // 时，按实现约定应优先判为 sudo-blocked。
  const mixed = 'operation not permitted; a password is required';
  assert.strictEqual(classifySudoFailure(mixed), 'sudo-blocked');
});

// ---------------------------------------------------------------------------
// TC-DASH-002 [P1] isPortListening()：端口监听判断
// ---------------------------------------------------------------------------

test('TC-DASH-002 未占用的高位端口应返回 false', () => {
  // 选一个几乎肯定空闲的端口；该函数底层用 lsof/netstat，无监听时应返回 false。
  const unlikelyPort = 59321;
  assert.strictEqual(isPortListening(unlikelyPort), false);
});

test('TC-DASH-002 真实监听端口应返回 true', async () => {
  const net = require('node:net');
  const server = net.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try {
    assert.strictEqual(isPortListening(port), true, `端口 ${port} 正在监听，应返回 true`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('TC-DASH-002 端口关闭后应返回 false', async () => {
  const net = require('node:net');
  const server = net.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  assert.strictEqual(isPortListening(port), false, `端口 ${port} 已关闭，应返回 false`);
});
