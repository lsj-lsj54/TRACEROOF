/**
 * 端到端回归测试：直接调用真实后端接口，覆盖用户反馈的三个问题
 *
 * 用法：
 *   node e2e-test.mjs                       # 默认打到 8666
 *   API_BASE=http://localhost:8668/api node e2e-test.mjs
 */
const BASE = process.env.API_BASE || 'http://localhost:8666/api';

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

/* ------------------------------ HTTP 封装（带 Cookie jar） ------------------------------ */
const cookieJar = new Map();

async function http(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (cookieJar.size > 0) {
    headers.Cookie = [...cookieJar].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json; charset=utf-8';
  }
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const setCookie = res.headers.get('set-cookie');
  if (setCookie) {
    setCookie.split(/,(?=[^;]+=)/).forEach((one) => {
      const [pair] = one.split(';');
      const i = pair.indexOf('=');
      cookieJar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
    });
  }
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch (e) {
    throw new Error(`返回不是 JSON: ${text.slice(0, 120)}`);
  }
  return json;
}

/* ============================== 1. 登录 ============================== */
console.log(`\n[1] 登录与会话（${BASE}）`);
const login = await http('/user/login', {
  method: 'POST',
  body: { userAccount: 'lsj', userPassword: '123456' },
});
check('登录成功', login.code === 0, `code=${login.code}`);
check('会话 Cookie 已下发', cookieJar.has('JSESSIONID'));

const me = await http('/user/get/login');
check('带会话查询当前用户成功', me.code === 0, `userAccount=${me.data && me.data.userAccount}`);

/* ============================== 2. Long 精度 ============================== */
console.log('\n[2] Long 主键精度（问题 2 的根因）');
const myId = me.data.id;
check(
  '用户 id 以字符串返回（超出 JS 安全范围）',
  typeof myId === 'string',
  `typeof=${typeof myId} value=${myId}`,
);
check(
  '用户 id 数值与数据库一致，无精度丢失',
  String(myId) === '2109210339268718593',
  `${myId}`,
);

/* ============================== 3. 分页 total ============================== */
console.log('\n[3] 分页 total（问题 3 的根因）');
const page1 = await http('/evidence/list/page/vo', {
  method: 'POST',
  body: { current: 1, pageSize: 12, sortField: 'createTime', sortOrder: 'descend' },
});
check('公开列表请求成功', page1.code === 0);
const d1 = page1.data;
check('total 不再是 0', d1.total > 0, `total=${d1.total} pages=${d1.pages} records=${d1.records.length}`);
check(
  'total 与 records 自洽',
  d1.records.length === Math.min(d1.size, d1.total),
  `records=${d1.records.length} size=${d1.size} total=${d1.total}`,
);
check('total / size / current 仍是数字类型', typeof d1.total === 'number' && typeof d1.size === 'number');

/* ============================== 4. 影像 id 精度 + 详情 ============================== */
console.log('\n[4] 影像 id 精度与详情接口（问题 2）');
const pic = d1.records[0];
check('列表返回了影像', !!pic, pic ? `name=${pic.name}` : '');
check('影像 id 以字符串返回', typeof pic.id === 'string', `typeof=${typeof pic.id} value=${pic.id}`);
check(
  '影像 id 是 19 位雪花 id 字符串（数字形式会丢精度）',
  /^\d{19}$/.test(String(pic.id)),
  `${pic.id}`,
);
check(
  '影像 id 未被 JS 精度改写成 ...000 结尾',
  !/000$/.test(String(pic.id)),
  `${pic.id}`,
);
check(
  '影像 userId 也是 19 位雪花 id 字符串（无精度丢失）',
  typeof pic.userId === 'string' && /^\d{19}$/.test(String(pic.userId)),
  `typeof=${typeof pic.userId} value=${pic.userId}`,
);

const detail = await http(`/evidence/get/vo?id=${encodeURIComponent(pic.id)}`);
check('用列表返回的 id 查详情成功（原来报「请求数据不存在」）', detail.code === 0, `code=${detail.code} message=${detail.message || ''}`);
check(
  '详情返回的 id 与请求一致',
  !!detail.data && String(detail.data.id) === String(pic.id),
  detail.data ? `${detail.data.id}` : '详情为空',
);
check(
  '详情返回的 userId 与列表一致',
  !!detail.data && String(detail.data.userId) === String(pic.userId),
  detail.data ? `${detail.data.userId}` : '详情为空',
);

/* ============================== 5. 我的影像 ============================== */
console.log('\n[5] 我的影像（问题 3）');
const mine = await http('/evidence/list/page/vo', {
  method: 'POST',
  body: { current: 1, pageSize: 12, userId: myId, sortField: 'createTime', sortOrder: 'descend' },
});
check('我的影像请求成功', mine.code === 0);
check('total 不再是 0', mine.data.total > 0, `total=${mine.data.total} records=${mine.data.records.length}`);
check(
  '返回的记录都属于当前用户',
  mine.data.records.every((item) => String(item.userId) === String(myId)),
);

/* ============================== 6. 管理员接口 ============================== */
console.log('\n[6] 管理员接口（问题 3 里提到的「影像管理」）');
const adminList = await http('/evidence/list/page', {
  method: 'POST',
  body: { current: 1, pageSize: 10, sortField: 'createTime', sortOrder: 'descend' },
});
check('影像管理列表成功', adminList.code === 0, `code=${adminList.code}`);
check('管理列表 total 正常', adminList.data.total > 0, `total=${adminList.data.total}`);
check(
  '实体里的 tags 是 JSON 字符串（前端已按此解析）',
  adminList.data.records.every((item) => item.tags === null || typeof item.tags === 'string'),
  `示例 tags=${JSON.stringify(adminList.data.records[0].tags)}`,
);

const userList = await http('/user/list/page/vo', {
  method: 'POST',
  body: { current: 1, pageSize: 10, sortField: 'createTime', sortOrder: 'descend' },
});
check('用户管理列表成功', userList.code === 0, `code=${userList.code}`);
check('用户列表 total 正常', userList.data.total > 0, `total=${userList.data.total}`);

/* ============================== 7. 翻页行为 ============================== */
console.log('\n[7] 翻页是否真的生效（验证 limit 被拼接）');
const all = await http('/evidence/list/page/vo', {
  method: 'POST',
  body: { current: 1, pageSize: 1, sortField: 'createTime', sortOrder: 'descend' },
});
check(
  'pageSize=1 时只返回 1 条（说明 limit 生效）',
  all.data.records.length === 1,
  `records=${all.data.records.length} total=${all.data.total}`,
);
check(
  'pageSize=1 时 pages 等于 total',
  all.data.pages === all.data.total,
  `pages=${all.data.pages} total=${all.data.total}`,
);

/* ============================== 8. 上传体积边界 ============================== */
console.log('\n[8] 上传体积边界（曾出现 1MiB 以上必失败）');
/**
 * 经代理上传指定体积的内容，判断是否被服务端体积限制拦下。
 * 用全零内容，与影像格式无关，只测「请求体大小」这一条链路。
 */
async function probeUploadSize(size) {
  const form = new FormData();
  form.append('file', new Blob([new Uint8Array(size)]), `size-probe-${size}.bin`);
  const headers = {};
  if (cookieJar.size > 0) {
    headers.Cookie = [...cookieJar].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  const res = await fetch(`${BASE}/file/test/upload`, { method: 'POST', headers, body: form });
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch (e) {
    return { code: -1, message: `非 JSON 响应：${text.slice(0, 60)}` };
  }
}

const boundaryCases = [
  ['1 MiB', 1024 * 1024],
  ['1.5 MiB', Math.round(1.5 * 1024 * 1024)],
  ['3 MiB', 3 * 1024 * 1024],
];
for (const [label, size] of boundaryCases) {
  const r = await probeUploadSize(size);
  // 体积限制放开后，即使内容不是影像，也应该是「有效影像」这类业务错误，而不是系统错误
  const blockedBySize = r.code === 50000 && /系统错误/.test(r.message || '');
  check(
    `${label} 请求不再被体积限制拦下`,
    !blockedBySize,
    `code=${r.code} message=${r.message}`,
  );
}

/* ============================== 9. 档案库 ============================== */
console.log('\n[9] 档案库：级别字典 / 创建 / 查询 / 重命名 / 删除');
const levelList = await http('/archive/list/level');
check(
  '档案库级别字典返回 3 个级别',
  levelList.code === 0 && Array.isArray(levelList.data) && levelList.data.length === 3,
  `code=${levelList.code} len=${levelList.data && levelList.data.length}`,
);

// 用时间戳命名，避免重复执行时撞上「用户档案库已经存在」
const archiveName = `e2e档案库-${Date.now()}`;
const created = await http('/archive/add', {
  method: 'POST',
  body: { archiveName, archiveLevel: 0 },
});
check('创建档案库成功', created.code === 0, `code=${created.code} message=${created.message || ''}`);
const archiveId = created.data;
check('返回了新档案库 id', !!archiveId, `archiveId=${archiveId}`);

const myArchives = await http('/archive/my/list/page/vo', {
  method: 'POST',
  body: { current: 1, pageSize: 10, sortField: 'createTime', sortOrder: 'descend' },
});
check('我的档案库列表请求成功', myArchives.code === 0, `code=${myArchives.code}`);
const mineArchive = (myArchives.data.records || [])
  .find((item) => String(item.id) === String(archiveId));
check('列表里能找到刚创建的档案库', !!mineArchive);
check(
  '档案库限额已按级别自动填充',
  !!mineArchive && Number(mineArchive.maxCount) > 0 && Number(mineArchive.maxSize) > 0,
  mineArchive ? `maxCount=${mineArchive.maxCount} maxSize=${mineArchive.maxSize}` : '',
);
check(
  '档案库归当前登录用户所有',
  !!mineArchive && String(mineArchive.userId) === String(myId),
  mineArchive ? `userId=${mineArchive.userId}` : '',
);

const archiveVO = await http(`/archive/get/vo?id=${encodeURIComponent(archiveId)}`);
check(
  '按 id 查询档案库成功',
  archiveVO.code === 0 && String(archiveVO.data.id) === String(archiveId),
  `code=${archiveVO.code}`,
);

const archiveEvidences = await http('/evidence/list/page/vo', {
  method: 'POST',
  body: { current: 1, pageSize: 10, archiveId },
});
check(
  '查询档案库内的影像成功（新档案库应为空）',
  archiveEvidences.code === 0 && archiveEvidences.data.total === 0,
  `code=${archiveEvidences.code} total=${archiveEvidences.data && archiveEvidences.data.total}`,
);

/* 上传到档案库：同时回归「档案库用量随影像增删变化」这条链路。
   updateArchiveUsage 依赖 MyBatis-Plus 的 wrapper 能力，用链式 wrapper 会在运行时报
   「can not use this method for getSqlFirst」，只有真连数据库才暴露得出来。 */
const uploadSource = pic.url;
const uploaded = await http('/evidence/upload/url', {
  method: 'POST',
  body: { evidenceUrl: uploadSource, archiveId },
});
check('上传影像到档案库成功', uploaded.code === 0, `code=${uploaded.code} message=${uploaded.message || ''}`);
const uploadedPic = uploaded.data || {};
check('影像归属到了目标档案库', String(uploadedPic.archiveId) === String(archiveId), `archiveId=${uploadedPic.archiveId}`);

const archiveAfterUpload = await http(`/archive/get/vo?id=${encodeURIComponent(archiveId)}`);
check(
  '档案库影像数量 +1',
  Number(archiveAfterUpload.data.totalCount) === 1,
  `totalCount=${archiveAfterUpload.data.totalCount}`,
);
check(
  '档案库已用容量等于影像体积',
  Number(archiveAfterUpload.data.totalSize) === Number(uploadedPic.picSize),
  `totalSize=${archiveAfterUpload.data.totalSize} picSize=${uploadedPic.picSize}`,
);
check(
  '档案库里的影像不会出现在公共案例库',
  !((await http('/evidence/list/page/vo', {
    method: 'POST',
    body: { current: 1, pageSize: 20, sortField: 'createTime', sortOrder: 'descend' },
  })).data.records || []).some((item) => String(item.id) === String(uploadedPic.id)),
);

const deletedEvidence = await http('/evidence/delete', { method: 'POST', body: { id: uploadedPic.id } });
check('删除档案库内影像成功', deletedEvidence.code === 0, `code=${deletedEvidence.code} message=${deletedEvidence.message || ''}`);
const archiveAfterDelete = await http(`/archive/get/vo?id=${encodeURIComponent(archiveId)}`);
check(
  '删除影像后档案库用量回落',
  Number(archiveAfterDelete.data.totalCount) === 0 && Number(archiveAfterDelete.data.totalSize) === 0,
  `totalCount=${archiveAfterDelete.data.totalCount} totalSize=${archiveAfterDelete.data.totalSize}`,
);

const renamed = await http('/archive/edit', {
  method: 'POST',
  body: { id: archiveId, archiveName: `${archiveName}-已改名` },
});
check('重命名档案库成功', renamed.code === 0, `code=${renamed.code} message=${renamed.message || ''}`);

const renameCheck = await http(`/archive/get/vo?id=${encodeURIComponent(archiveId)}`);
check(
  '重命名已生效',
  renameCheck.code === 0 && renameCheck.data.archiveName === `${archiveName}-已改名`,
  renameCheck.data ? `archiveName=${renameCheck.data.archiveName}` : '',
);

const deleted = await http('/archive/delete', { method: 'POST', body: { id: archiveId } });
check('删除档案库成功', deleted.code === 0, `code=${deleted.code} message=${deleted.message || ''}`);
const afterDelete = await http(`/archive/get/vo?id=${encodeURIComponent(archiveId)}`);
check(
  '删除后查档案库返回「数据不存在」',
  afterDelete.code === 40400,
  `code=${afterDelete.code} message=${afterDelete.message || ''}`,
);

/* ============================== 汇总 ============================== */
console.log(`\n================ 通过 ${pass} 项，失败 ${fail} 项 ================`);
process.exit(fail === 0 ? 0 : 1);
