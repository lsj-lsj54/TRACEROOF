/**
 * 并发测试：HTTP 接口并发压测 + WebSocket 协同编辑「编辑锁」并发
 *
 * 覆盖两个场景：
 *   1. HTTP 并发压测 —— 对读接口（MySQL 分表）与会话接口（Redis）各打一轮并发，
 *      统计成功率与延迟分布，验证高并发下服务不崩、不报错。
 *   2. 协同编辑「编辑锁」并发 —— 注册 N 个测试用户、建团队档案库、邀请为 editor、
 *      上传一张图，然后 N 个用户同一时刻抢编辑权，验证同一张图同一时刻只有一个人能成为编辑者，
 *      并验证编辑者退出后编辑锁能被释放、其他人可以接手。
 *
 * 零依赖：只用 Node 内置 fetch / WebSocket（Node >= 21）。
 *
 * 用法：
 *   node concurrency-test.mjs
 *   node concurrency-test.mjs --users 8 --http 200
 *   node concurrency-test.mjs --keep        # 跑完不清理测试数据（便于复查）
 *   # keep-alive 持续压测：固定 --http 个连接、复用连接打 --requests 个请求，
 *   # 客户端只占少量端口，能测出后端真实吞吐（推荐用来「跑更多 HTTP」）
 *   node concurrency-test.mjs --http 200 --requests 100000
 *
 * 环境变量：
 *   API_BASE   后端地址，默认 http://localhost:8666/api（WebSocket 地址由它推导）
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const API_BASE = process.env.API_BASE || 'http://localhost:8666/api';
const WS_BASE = API_BASE.replace(/^http/i, 'ws');

/* ------------------------------ 记录落盘（终端打印的同时 tee 到日志文件） ------------------------------ */
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const LOG_DIR = path.join(__dirname, 'test-logs');
const pad = (n) => String(n).padStart(2, '0');
const _now = new Date();
const LOG_FILE = path.join(LOG_DIR,
  `concurrency-${_now.getFullYear()}${pad(_now.getMonth() + 1)}${pad(_now.getDate())}` +
  `-${pad(_now.getHours())}${pad(_now.getMinutes())}${pad(_now.getSeconds())}.log`);
const logLines = [];
['log', 'warn', 'error'].forEach((level) => {
  const orig = console[level];
  console[level] = (...args) => {
    orig(...args);
    logLines.push(args.map((a) => (typeof a === 'string' ? a : JSON.stringify(a))).join(' '));
  };
});

/** 1x1 透明 PNG（base64），作为测试影像源，避免依赖外网图源（COS 有防盗链） */
const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

/* ------------------------------ 参数解析 ------------------------------ */
const args = process.argv.slice(2);
const readArg = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const hasFlag = (name) => args.includes(name);

const USER_COUNT = Number(readArg('--users', 5));        // 抢编辑权的用户数
const HTTP_CONCURRENCY = Number(readArg('--http', 100)); // HTTP 并发连接数
const REQUEST_COUNT = Number(readArg('--requests', 0));  // >0 时用 keep-alive 模式打这么多请求
const KEEP = hasFlag('--keep');                          // 是否保留测试数据

/* ------------------------------ 计数与断言 ------------------------------ */
let pass = 0;
let fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) {
    pass += 1;
    console.log(`  PASS  ${name}${detail ? '  → ' + detail : ''}`);
  } else {
    fail += 1;
    console.log(`  FAIL  ${name}${detail ? '  → ' + detail : ''}`);
  }
};

/* ------------------------------ HTTP 封装 ------------------------------ */
/**
 * 递归提取 fetch 的底层错误（undici 的 AggregateError.errors / cause 链），
 * 把笼统的「fetch failed」还原成 ECONNREFUSED / socket hang up / ETIMEDOUT 等真实原因。
 */
function extractCause(e) {
  if (!e) return '';
  if (Array.isArray(e.errors) && e.errors.length > 0) {
    return e.errors.map(extractCause).filter(Boolean).join(' | ');
  }
  if (e.cause) {
    const inner = extractCause(e.cause);
    if (inner) return inner;
  }
  return e.message || e.code || '';
}

/**
 * 每个用户一个独立的 Cookie jar，避免互相串登录态。
 * 返回 { code, data, message } 并顺带收集 Set-Cookie。
 */
function makeClient() {
  const cookieJar = new Map();
  async function http(path, { method = 'GET', body } = {}) {
    const headers = {};
    if (cookieJar.size > 0) {
      headers.Cookie = [...cookieJar].map(([k, v]) => `${k}=${v}`).join('; ');
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }
    let res;
    try {
      res = await fetch(API_BASE + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (e) {
      // undici 的底层错误在 e.cause / e.errors 里（如 connect ECONNREFUSED / socket hang up）
      throw new Error(`网络错误: ${extractCause(e) || e.message}`);
    }
    const setCookie = res.headers.get('set-cookie');
    if (setCookie) {
      setCookie.split(/,(?=[^;]+=)/).forEach((one) => {
        const [pair] = one.split(';');
        const i = pair.indexOf('=');
        if (i > 0) cookieJar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
      });
    }
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch (e) {
      return { code: -1, message: `非 JSON 响应：${text.slice(0, 80)}` };
    }
  }
  /** 带 Cookie 的 multipart 上传（用于 /evidence/upload 文件上传） */
  async function httpForm(path, formData) {
    const headers = {};
    if (cookieJar.size > 0) {
      headers.Cookie = [...cookieJar].map(([k, v]) => `${k}=${v}`).join('; ');
    }
    const res = await fetch(API_BASE + path, { method: 'POST', headers, body: formData });
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch (e) {
      return { code: -1, message: `非 JSON 响应：${text.slice(0, 80)}` };
    }
  }
  return { http, httpForm, cookieJar };
}

/** 延迟统计 */
function latencyStats(msList) {
  const sorted = [...msList].sort((a, b) => a - b);
  const sum = msList.reduce((a, b) => a + b, 0);
  const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
  return {
    count: msList.length,
    min: sorted[0],
    max: sorted[sorted.length - 1],
    avg: Math.round(sum / msList.length),
    p50: pct(0.5),
    p95: pct(0.95),
  };
}

/** 一轮并发压测：跑 concurrency 个同样的请求，返回 { ok, fail, stats } */
async function stress(client, label, path, { method = 'POST', body } = {}, concurrency) {
  const start = Date.now();
  const results = await Promise.all(Array.from({ length: concurrency }, async () => {
    const t0 = Date.now();
    let r;
    try {
      r = await client.http(path, { method, body });
    } catch (e) {
      r = { code: -1, message: e.message };
    }
    const ok = r.code === 0;
    // 归类失败原因，便于区分「连接被拒 / 超时」与「业务报错」
    let errKind = null;
    if (!ok) {
      errKind = r.code === -1 ? `网络:${r.message}` : `业务(code=${r.code}):${r.message}`;
    }
    return { ok, ms: Date.now() - t0, errKind };
  }));
  const totalMs = Date.now() - start;
  const ok = results.filter((r) => r.ok).length;
  const bad = results.length - ok;
  const stats = latencyStats(results.map((r) => r.ms));
  console.log(
    `  [${label}] 并发 ${concurrency}，成功 ${ok}，失败 ${bad}，` +
    `总耗时 ${totalMs}ms，吞吐 ${Math.round((concurrency / totalMs) * 1000)} req/s`,
  );
  console.log(
    `            延迟 min=${stats.min}ms avg=${stats.avg}ms p50=${stats.p50}ms p95=${stats.p95}ms max=${stats.max}ms`,
  );
  if (bad > 0) {
    const byKind = new Map();
    results.forEach((r) => {
      if (!r.ok) byKind.set(r.errKind, (byKind.get(r.errKind) || 0) + 1);
    });
    const top = [...byKind.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3);
    top.forEach(([kind, n]) => console.log(`            ↳ 失败原因（${n} 个）：${kind}`));
  }
  return { ok, bad };
}

/**
 * keep-alive 持续压测：固定 concurrency 个并发连接（复用连接），总共打 requests 个请求。
 * <p>
 * 与 {@link stress}（一次性开 concurrency 个连接）不同，这里客户端始终只占 concurrency 个端口，
 * 不会被临时端口耗尽 / TIME_WAIT 卡住，能更真实地测出后端吞吐上限。
 */
async function stressKeepAlive(client, label, path, { method = 'POST', body } = {}, concurrency, requests) {
  const start = Date.now();
  const latencies = [];
  const errKinds = new Map();
  let next = 0;
  let okCount = 0;
  async function worker() {
    for (;;) {
      const idx = next++;
      if (idx >= requests) return;
      const t0 = Date.now();
      let r;
      try {
        r = await client.http(path, { method, body });
      } catch (e) {
        r = { code: -1, message: e.message };
      }
      latencies.push(Date.now() - t0);
      if (r.code === 0) {
        okCount += 1;
      } else {
        const kind = r.code === -1 ? `网络:${r.message}` : `业务(code=${r.code}):${r.message}`;
        errKinds.set(kind, (errKinds.get(kind) || 0) + 1);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, worker));
  const totalMs = Date.now() - start;
  const bad = requests - okCount;
  const stats = latencyStats(latencies);
  console.log(
    `  [${label}] ${concurrency} 连接 × ${requests} 请求（keep-alive），成功 ${okCount}，失败 ${bad}，` +
    `总耗时 ${totalMs}ms，吞吐 ${Math.round((requests / totalMs) * 1000)} req/s`,
  );
  console.log(
    `            延迟 min=${stats.min}ms avg=${stats.avg}ms p50=${stats.p50}ms p95=${stats.p95}ms max=${stats.max}ms`,
  );
  if (bad > 0) {
    [...errKinds.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3)
      .forEach(([kind, n]) => console.log(`            ↳ 失败原因（${n} 个）：${kind}`));
  }
  return { ok: okCount, bad };
}

/* ------------------------------ WebSocket 封装 ------------------------------ */
/**
 * 建立一个协同编辑 WebSocket 连接（携带 satoken Cookie）。
 * 握手被后端拒绝（未登录 / 无权限 / 影像不存在）时 reject。
 */
function openEditWs(evidenceId, cookie) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const url = `${WS_BASE}/ws/evidence/edit?evidenceId=${encodeURIComponent(evidenceId)}`;
    const ws = new WebSocket(url, {
      headers: { Cookie: `satoken=${cookie}`, Origin: 'http://localhost:8667' },
    });
    const client = {
      ws,
      opened: false,
      onMessage: null,
      onClose: null,
      send(obj) {
        if (ws.readyState === 1) ws.send(JSON.stringify(obj));
      },
      close() {
        if (ws.readyState === 0 || ws.readyState === 1) ws.close();
      },
    };
    ws.onopen = () => {
      client.opened = true;
      settled = true;
      resolve(client);
    };
    ws.onmessage = (e) => {
      let msg;
      try {
        msg = JSON.parse(e.data);
      } catch (err) {
        return;
      }
      if (typeof client.onMessage === 'function') client.onMessage(msg);
    };
    ws.onclose = (e) => {
      if (!settled) {
        settled = true;
        reject(new Error('握手被拒绝（未登录 / 无编辑权限 / 影像不存在）'));
        return;
      }
      if (typeof client.onClose === 'function') client.onClose(e);
    };
    ws.onerror = () => {
      if (!settled) {
        settled = true;
        reject(new Error('连接失败，请确认后端已启动'));
      }
    };
  });
}

/** 发送 ENTER_EDIT 并等待结果：editor（成为编辑者）/ rejected（被拒）/ timeout */
function waitForEnter(client, ownId, timeoutMs = 4000) {
  return new Promise((resolve) => {
    let done = false;
    const settle = (v) => {
      if (!done) {
        done = true;
        resolve(v);
      }
    };
    client.onMessage = (msg) => {
      if (msg.type === 'ENTER_EDIT' && msg.user && String(msg.user.id) === String(ownId)) {
        settle('editor');
      } else if (msg.type === 'ERROR') {
        settle('rejected');
      }
    };
    client.send({ type: 'ENTER_EDIT' });
    setTimeout(() => settle('timeout'), timeoutMs);
  });
}

/* ============================== 主流程 ============================== */
console.log(`\n并发测试开始：后端 ${API_BASE}`);
const loadMode = REQUEST_COUNT > 0
  ? `HTTP 持续压测：${HTTP_CONCURRENCY} 连接 × ${REQUEST_COUNT} 请求（keep-alive）`
  : `HTTP 一次性并发：${HTTP_CONCURRENCY} 连接`;
console.log(`  抢编辑权用户数：${USER_COUNT}    ${loadMode}${KEEP ? '    （跑完保留测试数据）' : ''}\n`);

// 一次性开太多连接会耗尽「临时端口」并堆积 TIME_WAIT，结果不再反映后端真实容量。
// 只有一次性并发模式（--requests 未开启）才提示；keep-alive 模式客户端只占少量端口，没有这个限制。
if (REQUEST_COUNT === 0 && HTTP_CONCURRENCY > 2000) {
  console.warn(
    '⚠️  提示：HTTP 并发超过 2000，单机客户端可能先撞上「临时端口耗尽 / TIME_WAIT 堆积」上限，\n' +
    '   出现的 EADDRINUSE / ETIMEDOUT / ECONNREFUSED 失败是测试端资源限制，不代表后端出错。\n' +
    '   建议用 100~500 并发验证正确性；想打更高吞吐请加 --requests（如 --http 200 --requests 100000）。\n',
  );
}

const createdUserIds = [];
const createdArchiveIds = [];
let createdEvidenceId = null;

try {
  /* ------------------------------ 准备：注册 N 个测试用户并登录 ------------------------------ */
  const stamp = Date.now();
  const users = [];
  for (let i = 0; i < USER_COUNT; i += 1) {
    const account = `ct${stamp}${i}`;
    const client = makeClient();
    const reg = await client.http('/user/register', {
      method: 'POST',
      body: { userAccount: account, userName: `并发测试${i}`, userPassword: '123456', checkPassword: '123456' },
    });
    if (reg.code !== 0) {
      throw new Error(`注册用户 ${account} 失败：${reg.message}`);
    }
    const userId = String(reg.data);
    createdUserIds.push(userId);
    const login = await client.http('/user/login', {
      method: 'POST',
      body: { userAccount: account, userPassword: '123456' },
    });
    if (login.code !== 0) {
      throw new Error(`登录用户 ${account} 失败：${login.message}`);
    }
    const satoken = client.cookieJar.get('satoken');
    users.push({ id: String(login.data.id), account, cookie: satoken, client });
  }
  check(`注册并登录 ${USER_COUNT} 个测试用户`, users.length === USER_COUNT, `账号前缀 ct${stamp}`);

  /* ============================== Part 1：HTTP 并发压测 ============================== */
  console.log('\n[Part 1] HTTP 接口并发压测');
  const mainUser = users[0];
  // --requests > 0 时用 keep-alive 持续压测，否则一次性并发
  const runLoad = (label, path, opts) => (REQUEST_COUNT > 0
    ? stressKeepAlive(mainUser.client, label, path, opts, HTTP_CONCURRENCY, REQUEST_COUNT)
    : stress(mainUser.client, label, path, opts, HTTP_CONCURRENCY));
  {
    const { ok, bad } = await runLoad(
      '影像分页列表（MySQL 分表）', '/evidence/list/page/vo',
      { method: 'POST', body: { current: 1, pageSize: 12 } },
    );
    check('影像分页列表并发全部成功', bad === 0, `成功 ${ok}，失败 ${bad}`);
  }
  {
    const { ok, bad } = await runLoad(
      '获取当前用户（Redis 会话）', '/user/get/login',
      { method: 'GET' },
    );
    check('获取当前用户并发全部成功', bad === 0, `成功 ${ok}，失败 ${bad}`);
  }

  /* ============================== Part 2：协同编辑「编辑锁」并发 ============================== */
  console.log('\n[Part 2] 协同编辑「编辑锁」并发');

  // 1. 用第一个用户建团队档案库
  const archiveName = `并发测试档案库-${stamp}`;
  const archiveRes = await mainUser.client.http('/archive/add', {
    method: 'POST',
    body: { archiveName, archiveType: 1, archiveLevel: 0 },
  });
  if (archiveRes.code !== 0) {
    throw new Error(`创建团队档案库失败：${archiveRes.message}`);
  }
  const archiveId = String(archiveRes.data);
  createdArchiveIds.push(archiveId);

  // 2. 邀请其余用户为 editor
  for (let i = 1; i < users.length; i += 1) {
    const invite = await mainUser.client.http('/archive/user/add', {
      method: 'POST',
      body: { archiveId, userId: users[i].id, archiveRole: 'editor' },
    });
    if (invite.code !== 0) {
      throw new Error(`邀请用户 ${users[i].account} 为 editor 失败：${invite.message}`);
    }
  }
  check(`邀请 ${USER_COUNT - 1} 名成员为 editor`, true, `archiveId=${archiveId}`);

  // 3. 上传一张图到团队档案库（生成 1x1 PNG 文件上传，避免 COS 防盗链挡住 URL 下载）
  const uploadForm = new FormData();
  uploadForm.append('file', new Blob([TINY_PNG], { type: 'image/png' }), 'tiny.png');
  uploadForm.append('archiveId', archiveId);
  const uploadRes = await mainUser.client.httpForm('/evidence/upload', uploadForm);
  if (uploadRes.code !== 0) {
    throw new Error(`上传测试影像到团队档案库失败：${uploadRes.message}`);
  }
  createdEvidenceId = String(uploadRes.data.id);
  check('上传测试影像到团队档案库', true, `evidenceId=${createdEvidenceId}`);

  // 4. 所有用户建立 WebSocket 连接
  const conns = [];
  for (const u of users) {
    try {
      const c = await openEditWs(createdEvidenceId, u.cookie);
      conns.push({ ...u, ws: c });
    } catch (e) {
      // 连接失败不中断整场测试，记下来统一断言
      conns.push({ ...u, ws: null, connectError: e.message });
    }
  }
  const connected = conns.filter((c) => c.ws);
  check(
    `所有 ${USER_COUNT} 个用户均成功建立 WebSocket 连接`,
    connected.length === USER_COUNT,
    `成功 ${connected.length}/${USER_COUNT}`,
  );

  // 5. 同时抢编辑权
  const raceResults = await Promise.all(conns.map((c) => {
    if (!c.ws) return { id: c.id, result: 'connect-fail' };
    return waitForEnter(c.ws, c.id).then((result) => ({ id: c.id, result }));
  }));
  const editors = raceResults.filter((r) => r.result === 'editor');
  const rejected = raceResults.filter((r) => r.result === 'rejected');
  const other = raceResults.filter((r) => !['editor', 'rejected'].includes(r.result));

  check(
    '并发抢编辑权：恰有 1 人成为编辑者',
    editors.length === 1,
    `editor=${editors.length} rejected=${rejected.length}${other.length ? ` other=${JSON.stringify(other)}` : ''}`,
  );
  check(
    '其余用户均被正确拒绝',
    rejected.length === USER_COUNT - 1,
    `rejected=${rejected.length}/${USER_COUNT - 1}`,
  );

  // 6. 编辑锁释放：编辑者退出后，其他人能接手
  if (editors.length === 1 && rejected.length >= 1 && connected.length === USER_COUNT) {
    const winner = conns.find((c) => String(c.id) === String(editors[0].id));
    const loser = conns.find((c) => String(c.id) === String(rejected[0].id));
    winner.ws.send({ type: 'EXIT_EDIT' });
    await new Promise((r) => setTimeout(r, 300)); // 等退出消息广播 + 锁释放
    const retry = await waitForEnter(loser.ws, loser.id, 4000);
    check('编辑者退出后，锁被释放、其他人可接手', retry === 'editor', `重试结果=${retry}`);
    // 接手者退出，避免影响清理
    loser.ws.send({ type: 'EXIT_EDIT' });
  } else {
    check('编辑者退出后，锁被释放、其他人可接手', false, '前置条件不满足，跳过');
  }

  // 关闭所有连接
  conns.forEach((c) => c.ws && c.ws.close());

  /* ============================== 清理 ============================== */
  if (!KEEP) {
    console.log('\n[清理] 删除测试数据');
    if (createdEvidenceId) {
      // 影像随档案库删除一并清理，这里不用单独删
    }
    for (const sid of createdArchiveIds) {
      try {
        await mainUser.client.http('/archive/delete', { method: 'POST', body: { id: sid } });
      } catch (e) { /* 忽略 */ }
    }
    for (const uid of createdUserIds) {
      try {
        await mainUser.client.http('/user/delete', { method: 'POST', body: { id: uid } });
      } catch (e) { /* 忽略 */ }
    }
    console.log('  已删除测试档案库与测试用户');
  } else {
    console.log(`\n[保留数据] 档案库=${createdArchiveIds.join(',')} 用户=${createdUserIds.join(',')}`);
  }
} catch (e) {
  console.error(`\n测试中断：${e.message}`);
  fail += 1;
}

/* ============================== 汇总 ============================== */
console.log(`\n================ 通过 ${pass} 项，失败 ${fail} 项 ================`);
// 把本次运行的完整输出写入带时间戳的日志文件
try {
  fs.mkdirSync(LOG_DIR, { recursive: true });
  fs.writeFileSync(LOG_FILE, logLines.join('\n') + '\n', 'utf8');
  console.log(`\n📄 本次记录已保存：${LOG_FILE}`);
} catch (e) {
  console.log(`\n⚠️ 保存记录失败：${e.message}`);
}
process.exit(fail === 0 ? 0 : 1);
