// dashboard 状态聚合 / 路径探测 纯函数测试，覆盖《项目测试计划与清单》§4.5
// TC-DASH-004（getSetupStatus 状态聚合）及构建产物路径探测、状态快照结构。
//
// 同样依赖 server.js 的 `require.main === module` 守卫：require 时不启动服务。

const { test } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const {
  getSetupStatus,
  getJarPath,
  getTunBinaryPath,
  getStatusAll,
} = require(path.join(__dirname, '..', 'server.js'));

// ---------------------------------------------------------------------------
// TC-DASH-004 getSetupStatus()：状态聚合逻辑
// ---------------------------------------------------------------------------

test('TC-DASH-004 getSetupStatus 返回结构完整且类型正确', () => {
  const s = getSetupStatus();
  assert.strictEqual(typeof s, 'object');
  assert.strictEqual(s.platform, process.platform, 'platform 应等于 process.platform');
  assert.strictEqual(typeof s.isWindows, 'boolean');
  assert.strictEqual(typeof s.isMacOS, 'boolean');
  assert.strictEqual(typeof s.sudoersConfigured, 'boolean');
  assert.strictEqual(typeof s.needsSetup, 'boolean');
  assert.strictEqual(typeof s.setupScript, 'string');
  assert.strictEqual(typeof s.jarBuilt, 'boolean');
  assert.strictEqual(typeof s.tunBuilt, 'boolean');
});

test('TC-DASH-004 needsSetup 的逻辑：仅在 macOS 且 sudoers 未配置时为 true', () => {
  const s = getSetupStatus();
  const expected = s.isMacOS && !s.sudoersConfigured;
  assert.strictEqual(s.needsSetup, expected,
    'needsSetup 必须等价于 isMacOS && !sudoersConfigured');
});

test('TC-DASH-004 非 macOS 平台 sudoersConfigured 恒为 true（不需要免密配置）', () => {
  const s = getSetupStatus();
  if (!s.isMacOS) {
    assert.strictEqual(s.sudoersConfigured, true);
    assert.strictEqual(s.needsSetup, false);
  } else {
    // macOS：仅断言字段存在，具体值取决于本机是否装过 sudoers 规则。
    assert.ok('sudoersConfigured' in s);
  }
});

test('TC-DASH-004 setupScript 指向 dashboard 目录下的脚本', () => {
  const s = getSetupStatus();
  assert.ok(s.setupScript.endsWith('setup-tun-permissions.sh'),
    `setupScript 应以 setup-tun-permissions.sh 结尾，实际: ${s.setupScript}`);
});

// ---------------------------------------------------------------------------
// 构建产物路径探测：jar / tun 二进制存在性判定
// ---------------------------------------------------------------------------

test('getJarPath 返回 null 或一个存在的 .jar 路径', () => {
  const fs = require('node:fs');
  const jar = getJarPath();
  if (jar !== null) {
    assert.ok(jar.endsWith('.jar'), 'jar 路径应以 .jar 结尾');
    assert.ok(fs.existsSync(jar), 'getJarPath 返回非 null 时该文件必须真实存在');
  } else {
    assert.strictEqual(jar, null);
  }
});

test('getTunBinaryPath 返回 null 或一个存在的二进制路径', () => {
  const fs = require('node:fs');
  const bin = getTunBinaryPath();
  if (bin !== null) {
    assert.ok(fs.existsSync(bin), 'getTunBinaryPath 返回非 null 时该文件必须真实存在');
  } else {
    assert.strictEqual(bin, null);
  }
});

test('getSetupStatus.jarBuilt / tunBuilt 与路径探测结果一致', () => {
  const s = getSetupStatus();
  assert.strictEqual(s.jarBuilt, !!getJarPath());
  assert.strictEqual(s.tunBuilt, !!getTunBinaryPath());
});

// ---------------------------------------------------------------------------
// getStatusAll()：状态快照结构
// ---------------------------------------------------------------------------

test('getStatusAll 为两个受管进程返回结构化状态', () => {
  const all = getStatusAll();
  for (const name of ['proxy-local', 'tun-adapter']) {
    assert.ok(all[name], `应包含 ${name} 的状态`);
    assert.strictEqual(typeof all[name].status, 'string');
    assert.ok('pid' in all[name]);
    assert.ok('uptime' in all[name]);
    assert.ok(all[name].uptime >= 0, 'uptime 不应为负');
  }
});

test('getStatusAll 在未启动时进程状态非 running 的进程 pid 为 null', () => {
  const all = getStatusAll();
  // 未被本进程 spawn 的服务，pid 必为 null（即便检测到外部运行，也只改 status 不给 pid）。
  for (const name of ['proxy-local', 'tun-adapter']) {
    if (!String(all[name].status).startsWith('running')) {
      assert.strictEqual(all[name].pid, null);
    }
  }
});
