/**
 * URL 上传端到端回归测试
 *
 * 做两件事：
 *  1. 起一个本地小 HTTP 服务，提供一张合法 PNG（也可返回超限/非影像等异常场景）；
 *  2. 调用后端 /evidence/upload/url，验证「下载 → 转存对象存储 → 入库」整条链路。
 *
 * 用本地图源而不是外网影像：不依赖外网、结果稳定、边界场景可控。
 *
 * 用法： node url-upload-test.mjs
 *      API_BASE=http://localhost:8668/api node url-upload-test.mjs
 */
import { createServer } from 'http';
import { deflateSync, crc32 } from 'zlib';

const BASE = process.env.API_BASE || 'http://localhost:8666/api';
const SOURCE_PORT = Number(process.env.SOURCE_PORT || 8669);

/* ---------------------------- 造一张合法 PNG ---------------------------- */
function makePng(width, height) {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  let p = 0;
  for (let y = 0; y < height; y++) {
    raw[p++] = 0;
    for (let x = 0; x < width; x++) {
      raw[p++] = (x * 7 + y * 3) % 256;
      raw[p++] = (x * 3 + y * 11) % 256;
      raw[p++] = (x * 13 + y * 5) % 256;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const GOOD_PNG = makePng(320, 240);
const BIG_PNG = makePng(1400, 1400); // 体积超过 2MB，用于验证超限拦截

/* ---------------------------- 本地图源服务 ---------------------------- */
const source = createServer((req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (path === '/good.png') {
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': GOOD_PNG.length });
    res.end(req.method === 'HEAD' ? undefined : GOOD_PNG);
    return;
  }
  if (path === '/big.png') {
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': BIG_PNG.length });
    res.end(req.method === 'HEAD' ? undefined : BIG_PNG);
    return;
  }
  if (path === '/notimage.txt') {
    const body = Buffer.from('this is definitely not an image', 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/plain', 'Content-Length': body.length });
    res.end(req.method === 'HEAD' ? undefined : body);
    return;
  }
  if (path === '/no-head.png') {
    // 模拟不支持 HEAD 的站点，验证后端能退回直接下载
    if (req.method === 'HEAD') {
      res.writeHead(405).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': GOOD_PNG.length });
    res.end(GOOD_PNG);
    return;
  }
  res.writeHead(404).end();
});

await new Promise((resolve) => source.listen(SOURCE_PORT, resolve));
console.log(`本地图源已启动: http://localhost:${SOURCE_PORT}`);
console.log(`  合法影像   ${(GOOD_PNG.length / 1024).toFixed(1)} KB`);
console.log(`  超大影像   ${(BIG_PNG.length / 1024).toFixed(1)} KB`);

/* ---------------------------- 后端请求 ---------------------------- */
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

const jar = new Map();
async function request(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (jar.size) headers.Cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const sc = res.headers.get('set-cookie');
  if (sc) {
    sc.split(/,(?=[^;]+=)/).forEach((one) => {
      const [pair] = one.split(';');
      const i = pair.indexOf('=');
      jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
    });
  }
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch (e) {
    return { code: -1, message: `非 JSON 响应：${text.slice(0, 80)}` };
  }
}

console.log(`\n[1] 登录 ${BASE}`);
const login = await request('/user/login', {
  method: 'POST',
  body: { userAccount: 'lsj', userPassword: '123456' },
});
check('登录成功', login.code === 0, `code=${login.code}`);
if (login.code !== 0) {
  console.log('\n登录失败，后续用例无法执行');
  source.close();
  process.exit(1);
}

console.log('\n[2] URL 上传：正常影像');
const good = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: `http://localhost:${SOURCE_PORT}/good.png` },
});
check('接口存在且上传成功', good.code === 0, `code=${good.code} message=${good.message || ''}`);
if (good.code === 0) {
  const pic = good.data;
  check('返回了对象存储地址', typeof pic.url === 'string' && pic.url.startsWith('http'), pic.url);
  check('记录了宽高', pic.picWidth === 320 && pic.picHeight === 240, `${pic.picWidth}x${pic.picHeight}`);
  check('记录了体积与格式', pic.picSize > 0 && !!pic.picFormat, `size=${pic.picSize} format=${pic.picFormat}`);
  check('id 是字符串（Long 精度已处理）', typeof pic.id === 'string', `typeof=${typeof pic.id}`);

  // 详情接口能查到，说明确实入库了
  const detail = await request(`/evidence/get/vo?id=${encodeURIComponent(pic.id)}`);
  check('能通过详情接口查到该影像', detail.code === 0, `code=${detail.code}`);

  // 清理测试数据
  const del = await request('/evidence/delete', { method: 'POST', body: { id: pic.id } });
  check('测试数据已清理', del.code === 0, `code=${del.code}`);
}

console.log('\n[3] URL 上传：不支持 HEAD 的站点（应回退为直接下载）');
const noHead = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: `http://localhost:${SOURCE_PORT}/no-head.png` },
});
check('仍然上传成功', noHead.code === 0, `code=${noHead.code} message=${noHead.message || ''}`);
if (noHead.code === 0) {
  await request('/evidence/delete', { method: 'POST', body: { id: noHead.data.id } });
}

console.log('\n[4] URL 上传：参数与安全的边界');
const blank = await request('/evidence/upload/url', { method: 'POST', body: { evidenceUrl: '   ' } });
check('空地址被拒绝', blank.code === 40000, `code=${blank.code} message=${blank.message}`);

const badScheme = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: 'ftp://localhost/x.png' },
});
check('非 http/https 协议被拒绝', badScheme.code === 40000, `code=${badScheme.code} message=${badScheme.message}`);

const malformed = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: 'not-a-url' },
});
check('非法地址被拒绝', malformed.code === 40000, `code=${malformed.code} message=${malformed.message}`);

const notImage = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: `http://localhost:${SOURCE_PORT}/notimage.txt` },
});
check('非影像类型被拒绝', notImage.code === 40000, `code=${notImage.code} message=${notImage.message}`);

const tooBig = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: `http://localhost:${SOURCE_PORT}/big.png` },
});
check('超过 2MB 被拒绝', tooBig.code === 40000, `code=${tooBig.code} message=${tooBig.message}`);

const notFound = await request('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: `http://localhost:${SOURCE_PORT}/nope.png` },
});
check('地址 404 时有明确报错', notFound.code !== 0, `code=${notFound.code} message=${notFound.message}`);

console.log('\n[5] 文件上传接口仍然可用（回归）');
const form = new FormData();
form.append('file', new Blob([GOOD_PNG]), 'regression.png');
const fileRes = await fetch(`${BASE}/evidence/upload`, {
  method: 'POST',
  headers: jar.size ? { Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') } : {},
  body: form,
});
const fileJson = await fileRes.json();
check('本地文件上传成功', fileJson.code === 0, `code=${fileJson.code} message=${fileJson.message || ''}`);
if (fileJson.code === 0) {
  await request('/evidence/delete', { method: 'POST', body: { id: fileJson.data.id } });
}

console.log(`\n================ 通过 ${pass} 项，失败 ${fail} 项 ================`);
source.close();
process.exit(fail === 0 ? 0 : 1);
