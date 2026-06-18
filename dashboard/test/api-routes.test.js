// TC-DASH-003 [P2]：Dashboard API 路由与状态管理测试
// 覆盖 getStatusAll 结构、MIME 类型映射、配置读取等纯函数逻辑。
//
// 运行：node --test

const { test } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const fs = require('node:fs');

const {
  getStatusAll,
  getSetupStatus,
  classifySudoFailure,
} = require(path.join(__dirname, '..', 'server.js'));

// ---------------------------------------------------------------------------
// TC-DASH-003a：getStatusAll 返回的状态结构在各种条件下保持一致
// ---------------------------------------------------------------------------

test('TC-DASH-003 getStatusAll 初始状态所有服务为 stopped', () => {
  const all = getStatusAll();
  // 在测试环境中（未启动任何服务），两个服务应为 stopped 或 running (external)
  for (const name of ['proxy-local', 'tun-adapter']) {
    assert.ok(all[name], `应包含 ${name}`);
    const status = all[name].status;
    // 允许 stopped 或 running (external)（如果本机恰好有服务在跑）
    assert.ok(
      status === 'stopped' || status.includes('running'),
      `${name} 状态应为 stopped 或 running 变体，实际: ${status}`
    );
  }
});

test('TC-DASH-003 getStatusAll uptime 为 0 当服务未启动', () => {
  const all = getStatusAll();
  for (const name of ['proxy-local', 'tun-adapter']) {
    if (all[name].status === 'stopped') {
      assert.strictEqual(all[name].uptime, 0, `${name} 未启动时 uptime 应为 0`);
      assert.strictEqual(all[name].startedAt, null, `${name} 未启动时 startedAt 应为 null`);
    }
  }
});

// ---------------------------------------------------------------------------
// TC-DASH-003b：classifySudoFailure 边界情况补充
// ---------------------------------------------------------------------------

test('TC-DASH-003 classifySudoFailure 处理超长字符串不崩溃', () => {
  const longStr = 'x'.repeat(10000) + ' operation not permitted ' + 'y'.repeat(10000);
  assert.strictEqual(classifySudoFailure(longStr), 'sudo-blocked');
});

test('TC-DASH-003 classifySudoFailure 处理数字输入不崩溃', () => {
  // 虽然类型不对，但实现应容错
  assert.strictEqual(classifySudoFailure(12345), 'other');
});

// ---------------------------------------------------------------------------
// TC-DASH-003c：配置文件路径存在性验证
// ---------------------------------------------------------------------------

test('TC-DASH-003 项目配置文件路径可达', () => {
  const projectRoot = path.resolve(__dirname, '..', '..');
  const configs = [
    path.join(projectRoot, 'proxy-local', 'src', 'main', 'resources', 'proxy.yml'),
    path.join(projectRoot, 'tun-adapter', 'config', 'tun.toml'),
  ];
  for (const cfg of configs) {
    assert.ok(fs.existsSync(cfg), `配置文件应存在: ${cfg}`);
  }
});

// ---------------------------------------------------------------------------
// TC-DASH-003d：server.js 模块导出完整性
// ---------------------------------------------------------------------------

test('TC-DASH-003 server.js 导出所有必需的纯函数', () => {
  const mod = require(path.join(__dirname, '..', 'server.js'));
  const expectedExports = [
    'classifySudoFailure',
    'isPortListening',
    'getSetupStatus',
    'getJarPath',
    'getTunBinaryPath',
    'getStatusAll',
  ];
  for (const name of expectedExports) {
    assert.strictEqual(typeof mod[name], 'function', `应导出函数: ${name}`);
  }
});
