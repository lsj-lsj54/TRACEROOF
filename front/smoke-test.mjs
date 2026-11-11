/**
 * 前端模块冒烟检查（仅用于本地验证，可随时删除）
 *
 * 做法：搭一个极简的 DOM 桩，真实 import 所有模块。
 * 这样不仅能查出语法错误，还能查出 import 路径错误和首屏渲染阶段的运行时错误。
 *
 * 用法： node smoke-test.mjs
 */

/** 记录所有被赋过 innerHTML 的节点，便于检查渲染结果 */
const renderedHtml = [];

/** 极简元素桩 */
function createElementStub(tag = 'div') {
  const el = {
    tagName: String(tag).toUpperCase(),
    _html: '',
    children: [],
    style: {},
    dataset: {},
    value: '',
    checked: false,
    disabled: false,
    hidden: false,
    type: '',
    files: [],
    classList: {
      _set: new Set(),
      add(...names) { names.forEach((n) => this._set.add(n)); },
      remove(...names) { names.forEach((n) => this._set.delete(n)); },
      toggle(name, force) {
        const on = force === undefined ? !this._set.has(name) : force;
        if (on) {
          this._set.add(name);
        } else {
          this._set.delete(name);
        }
        return on;
      },
      contains(name) { return this._set.has(name); },
    },
    get innerHTML() {
      return this._html;
    },
    set innerHTML(html) {
      this._html = String(html);
      renderedHtml.push(this._html);
    },
    addEventListener() {},
    removeEventListener() {},
    appendChild(child) { this.children.push(child); return child; },
    removeChild() {},
    querySelector() { return createElementStub(); },
    querySelectorAll() { return []; },
    closest() { return createElementStub(); },
    setAttribute() {},
    getAttribute() { return null; },
    removeAttribute() {},
    click() {},
    focus() {},
    getContext() { return { drawImage() {} }; },
    toBlob() {},
  };
  return el;
}

/** 安装 DOM / 浏览器全局桩 */
function installDom() {
  const documentStub = {
    body: createElementStub('body'),
    createElement: (tag) => createElementStub(tag),
    querySelector: () => createElementStub(),
    querySelectorAll: () => [],
    addEventListener() {},
    removeEventListener() {},
  };
  globalThis.document = documentStub;
  globalThis.window = {
    location: { hash: '#/' },
    addEventListener() {},
    removeEventListener() {},
  };
  globalThis.location = globalThis.window.location;
  globalThis.MutationObserver = class {
    observe() {}

    disconnect() {}
  };
  globalThis.Image = class {
    set src(value) { this._src = value; }

    get src() { return this._src; }
  };
  globalThis.alert = () => {};
  // 拦截所有后端请求，避免冒烟测试依赖后端
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ code: 40100, data: null, message: '未登录' }),
  });
}

installDom();

const modules = [
  './js/api.js',
  './js/ui.js',
  './js/app.js',
  './js/components/evidence.js',
  './js/components/lightbox.js',
  './js/views/auth.js',
  './js/views/gallery.js',
  './js/views/mine.js',
  './js/views/archive.js',
  './js/views/archiveDetail.js',
  './js/views/analyze.js',
  './js/views/adminEvidence.js',
  './js/views/adminUser.js',
];

let failed = 0;
for (const spec of modules) {
  try {
    await import(spec);
    console.log(`OK   ${spec}`);
  } catch (e) {
    failed += 1;
    console.log(`FAIL ${spec}`);
    console.log(`     ${e.stack ? e.stack.split('\n').slice(0, 3).join('\n     ') : e.message}`);
  }
}

// app.js 是异步 bootstrap，等它跑完再检查首屏
await new Promise((resolve) => setTimeout(resolve, 300));

const shell = renderedHtml.find((html) => html.includes('class="header"'));
if (shell) {
  console.log('OK   首屏骨架渲染成功（含 header / nav / main）');
  if (!shell.includes('id="view"')) {
    failed += 1;
    console.log('FAIL 骨架中缺少 #view 容器');
  }
  if (!shell.includes('我的档案库')) {
    failed += 1;
    console.log('FAIL 导航里缺少「我的档案库」入口');
  }
} else {
  failed += 1;
  console.log('FAIL 未渲染出页面骨架，最后一段输出：');
  console.log(renderedHtml.slice(-1)[0]);
}

/* ------------------------------ 档案库页面渲染检查 ------------------------------ */

/** 让 fetch 返回「已登录 + 有一个档案库」，用于验证档案库页面的渲染分支 */
function installArchiveFetchStub() {
  const archiveSample = {
    id: '10',
    archiveName: '旅行照片',
    archiveLevel: 0,
    maxSize: 104857600,
    maxCount: 100,
    totalSize: 2048,
    totalCount: 2,
    userId: '1',
    createTime: '2026-10-14 10:00:00',
  };
  globalThis.fetch = async (url) => {
    const target = String(url);
    let result = { code: 40100, data: null, message: '未登录' };
    if (target.includes('/archive/my/list/page/vo')) {
      result = { code: 0, data: { current: 1, size: 12, total: 1, records: [archiveSample] } };
    } else if (target.includes('/archive/get/vo')) {
      result = { code: 0, data: archiveSample };
    } else if (target.includes('/evidence/list/page/vo')) {
      result = { code: 0, data: { current: 1, size: 12, total: 0, records: [] } };
    } else if (target.includes('/archive/user/list')) {
      // 团队档案库的成员列表（archiveDetail 进入团队档案库时调用）
      result = {
        code: 0,
        data: [
          {
            id: '100',
            archiveId: '10',
            userId: '1',
            archiveRole: 'admin',
            user: { id: '1', userAccount: 'tester', userName: '测试用户' },
          },
        ],
      };
    } else if (target.includes('/archive/user/list/my')) {
      // 我加入的团队档案库（列表页调用）
      result = { code: 0, data: [] };
    }
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify(result),
    };
  };
}

/** 等待视图内部的异步加载完成 */
const tick = () => new Promise((resolve) => setTimeout(resolve, 50));

/** 断言最近渲染出来的 HTML 里包含某段内容 */
function expectRendered(label, ...keywords) {
  const html = renderedHtml.join('\n');
  const missing = keywords.filter((word) => !html.includes(word));
  if (missing.length === 0) {
    console.log(`OK   ${label}`);
    return;
  }
  failed += 1;
  console.log(`FAIL ${label}（缺少：${missing.join('、')}）`);
}

installArchiveFetchStub();
const { state } = await import('./js/ui.js');
state.setCurrentUser({
  id: '1', userAccount: 'tester', userName: '测试用户', userRole: 'user',
});

const archiveView = (await import('./js/views/archive.js')).default;
renderedHtml.length = 0;
archiveView.render(createElementStub('div'));
await tick();
expectRendered('我的档案库页渲染出档案库卡片与用量条', 'archive-card', '旅行照片', 'usage-fill', '创建档案库');

const archiveDetailView = (await import('./js/views/archiveDetail.js')).default;
renderedHtml.length = 0;
archiveDetailView.render(createElementStub('div'), { id: '10' });
await tick();
expectRendered('档案库详情页渲染出档案库信息与上传入口', 'archive-detail-head', '旅行照片', '上传影像');

renderedHtml.length = 0;
archiveDetailView.render(createElementStub('div'), {});
await tick();
expectRendered('档案库详情页缺少 id 时给出提示', '缺少档案库 id');

/* ------------------------------ 档案库分析页渲染检查 ------------------------------ */

/** 冒烟环境没有 ECharts，分析页应优雅降级而不是抛错 */
const analyzeView = (await import('./js/views/analyze.js')).default;
renderedHtml.length = 0;
analyzeView.render(createElementStub('div'), {});
await tick();
expectRendered('分析页在无 ECharts 环境下给出友好提示', '图表组件');
analyzeView.destroy();

console.log(`\n共检查 ${modules.length} 个模块，失败 ${failed} 项`);
process.exit(failed === 0 ? 0 : 1);
