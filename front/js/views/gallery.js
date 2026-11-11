/**
 * 影像浏览页（首页）：分页、搜索、分类筛选、详情
 */
import { evidenceApi, USE_CACHE } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime, renderPager, renderEmpty,
  debounce, CATEGORY_OPTIONS, REVIEW_STATUS, normalizePage,
  renderSkeleton, revealOnScroll, bindImageFade, attachRipple, attachRippleAll,
} from '../ui.js';
import { openEvidenceDetail, openUploadModal, thumbUrl } from '../components/evidence.js';
import { openLightbox } from '../components/lightbox.js';

/** 每页数量，后端限制不能超过 20 */
const PAGE_SIZE = 12;

/** 颜色选择器未激活时回填的占位色（不用于查询，仅让原生取色器有初始显示） */
const DEFAULT_PICKER_COLOR = '#736246';

/** 主色调查询的容差上限（RGB 欧氏距离最大约 441，超出即全量） */
const MAX_COLOR_DISTANCE = 441;

/** 后端默认容差，与 EvidenceServiceImpl.DEFAULT_COLOR_DISTANCE 保持一致 */
const DEFAULT_COLOR_DISTANCE = 80;

const query = {
  current: 1,
  pageSize: PAGE_SIZE,
  // 首页是公共案例库，archiveId 固定传 0（后端约定：0 = 公共案例库，>0 = 私有/团队档案库）
  archiveId: 0,
  searchText: '',
  category: '',
  // 按主色调（腾讯云 imageAve 返回）查图：picColor 为空表示不按颜色筛选
  picColor: '',
  colorDistance: DEFAULT_COLOR_DISTANCE,
  sortField: 'createTime',
  sortOrder: 'descend',
};

/** 按色筛选时重置页码并重新拉取 */
function reload(el) {
  query.current = 1;
  load(el);
}

/** 取色器变化时应用颜色筛选（同时同步工具栏控件状态） */
function applyColor(hex, el) {
  query.picColor = (hex || '').toUpperCase();
  const ci = $('#color-input', el);
  if (ci && query.picColor) {
    ci.value = query.picColor;
  }
  syncColorControls(el);
  reload(el);
}

/** 清除颜色筛选，恢复默认取色器与容差 */
function clearColor(el) {
  query.picColor = '';
  query.colorDistance = DEFAULT_COLOR_DISTANCE;
  const ci = $('#color-input', el);
  if (ci) {
    ci.value = DEFAULT_PICKER_COLOR;
  }
  const cd = $('#color-dist', el);
  if (cd) {
    cd.value = String(DEFAULT_COLOR_DISTANCE);
  }
  const cv = $('#color-dist-val', el);
  if (cv) {
    cv.textContent = String(DEFAULT_COLOR_DISTANCE);
  }
  syncColorControls(el);
  reload(el);
}

/**
 * 同步颜色筛选相关控件的状态：
 * - 已选色时显示「清除颜色」按钮，并禁用排序下拉（后端会按颜色距离升序，排序参数被忽略）
 * - 未选色时隐藏清除按钮、启用排序
 */
function syncColorControls(el) {
  const clearBtn = $('#color-clear', el);
  if (clearBtn) {
    clearBtn.hidden = !query.picColor;
  }
  const sortSelect = $('#sort-select', el);
  if (sortSelect) {
    sortSelect.disabled = !!query.picColor;
  }
}

/** 拉取当前页数据并渲染 */
async function load(el) {
  const grid = $('#grid', el);
  // 骨架屏占位：与真实卡片同构，避免加载时页面高度剧烈跳动
  grid.innerHTML = renderSkeleton(PAGE_SIZE);
  try {
    // 开启缓存开关后走带多级缓存的接口，减少数据库压力
    const data = USE_CACHE
      ? await evidenceApi.listVOByPageWithCache({ ...query })
      : await evidenceApi.listVOByPage({ ...query });
    renderGrid(el, normalizePage(data));
  } catch (e) {
    grid.innerHTML = renderEmpty(e.message);
  }
}

/** 渲染影像卡片网格 */
function renderGrid(el, page) {
  const records = page.records || [];
  const grid = $('#grid', el);
  const pager = $('#pager', el);

  if (records.length === 0) {
    grid.innerHTML = renderEmpty('没有找到影像，换个关键词试试');
  } else {
    grid.innerHTML = records.map((item) => {
      const owner = item.user ? item.user.userName || item.user.userAccount : '未知用户';
      const tags = (item.tags || []).slice(0, 3)
        .map((tag) => `<span class="tag tag-sm">${escapeHtml(tag)}</span>`).join('');
      const review = REVIEW_STATUS[item.reviewStatus];
      // 主色调色块：后端在上传时由腾讯云 imageAve 回填，历史影像该字段为空则不展示
      const colorSwatch = item.picColor
        ? `<button class="color-swatch" type="button" data-color="${escapeHtml(item.picColor)}"
            title="按此主色调查找相似影像">主色
            <span class="color-dot" style="background:${escapeHtml(item.picColor)}"></span>
            <span class="color-hex">${escapeHtml(item.picColor.toUpperCase())}</span>
          </button>`
        : '';
      return `
        <article class="card reveal" data-id="${item.id}">
          <div class="card-img">
            <img src="${escapeHtml(thumbUrl(item))}" alt="${escapeHtml(item.name)}" loading="lazy" data-fade>
            ${review ? `<span class="card-badge ${review.cls}">${review.text}</span>` : ''}
            <div class="card-quick">
              <button class="btn btn-sm" data-action="zoom" type="button" title="全屏查看">放大</button>
              <button class="btn btn-sm" data-action="detail" type="button" title="查看详细信息">详情</button>
            </div>
          </div>
          <div class="card-body">
            <h3 class="card-title" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</h3>
            <p class="card-desc" title="${escapeHtml(item.introduction)}">
              ${escapeHtml(item.introduction) || '暂无简介'}
            </p>
            <div class="card-tags">${tags}</div>
            ${colorSwatch}
            <div class="card-meta">
              <span>${escapeHtml(owner)}</span>
              <span>${formatTime(item.createTime)}</span>
            </div>
          </div>
        </article>`;
    }).join('');
  }

  pager.innerHTML = renderPager({
    current: page.current,
    pageSize: page.size,
    total: page.total,
  });

  /** 打开全屏查看器，并把本页影像都带进去，支持左右切换 */
  function openViewer(id) {
    const index = records.findIndex((item) => String(item.id) === String(id));
    openLightbox({ evidences: records, index: index < 0 ? 0 : index });
  }

  // 点击影像直接全屏查看（列表页最常见诉求就是「看清楚点」）
  $$('.card-img', grid).forEach((imgBox) => {
    imgBox.addEventListener('click', () => {
      openViewer(imgBox.closest('.card').dataset.id);
    });
  });

  // 快速操作：详情走原弹窗，放大走全屏查看器
  $$('[data-action]', grid).forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const id = btn.closest('.card').dataset.id;
      if (btn.dataset.action === 'zoom') {
        openViewer(id);
        return;
      }
      openEvidenceDetail(id, { onChanged: () => load(el) });
    });
  });

  // 点击主色块：以该图的主色调作为筛选色，进入「按色找相似」
  $$('.color-swatch', grid).forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      applyColor(btn.dataset.color, el);
      // 滚回顶部，方便看到筛选结果
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  });

  // 分页
  $$('[data-page]', pager).forEach((btn) => {
    btn.addEventListener('click', () => {
      const target = Number(btn.dataset.page);
      if (target < 1) {
        return;
      }
      query.current = target;
      load(el);
    });
  });

  // 影像淡入 / 卡片错峰入场的动效绑定（放在模板写入之后、事件绑定之后均可）
  bindImageFade(grid);
  revealOnScroll(grid);
  attachRippleAll(grid);
  $$('[data-action], .color-swatch', grid).forEach(attachRipple);
}

export default {
  /**
   * 渲染浏览页
   *
   * @param {HTMLElement} el 视图容器
   */
  render(el) {
    const categoryOptions = CATEGORY_OPTIONS
      .map((item) => `<option value="${item}" ${item === query.category ? 'selected' : ''}>${item}</option>`)
      .join('');

    el.innerHTML = `
      <section class="toolbar">
        <div class="toolbar-left">
          <input id="search-input" class="input" type="search" placeholder="搜索影像名称或简介"
                 value="${escapeHtml(query.searchText)}">
          <select id="category-select" class="input input-select">
            <option value="">全部分类</option>
            ${categoryOptions}
          </select>
          <div class="color-filter" id="color-filter">
            <label class="color-field" title="按主色调筛选相似影像">
              <span class="color-field-label">主色调</span>
              <input type="color" id="color-input" value="${DEFAULT_PICKER_COLOR}">
            </label>
            <label class="color-range" title="相似度容差，越大匹配越宽松">
              <span>容差 <b id="color-dist-val">${DEFAULT_COLOR_DISTANCE}</b></span>
              <input type="range" id="color-dist" min="0" max="${MAX_COLOR_DISTANCE}" step="5"
                     value="${DEFAULT_COLOR_DISTANCE}">
            </label>
            <button class="btn btn-sm" id="color-clear" type="button" hidden>清除颜色</button>
          </div>
        </div>
        <div class="toolbar-right">
          <select id="sort-select" class="input input-select">
            <option value="createTime-descend">最新上传</option>
            <option value="createTime-ascend">最早上传</option>
            <option value="picSize-descend">体积从大到小</option>
          </select>
          <button class="btn btn-primary" id="upload-btn" type="button">上传影像</button>
        </div>
      </section>
      <section id="grid" class="grid"></section>
      <div id="pager"></div>`;

    // 搜索防抖
    $('#search-input', el).addEventListener('input', debounce((e) => {
      query.searchText = e.target.value.trim();
      query.current = 1;
      load(el);
    }, 400));

    $('#category-select', el).addEventListener('change', (e) => {
      query.category = e.target.value;
      query.current = 1;
      load(el);
    });

    $('#sort-select', el).addEventListener('change', (e) => {
      const [sortField, sortOrder] = e.target.value.split('-');
      query.sortField = sortField;
      query.sortOrder = sortOrder;
      query.current = 1;
      load(el);
    });

    // 取色器：拖动即筛选，防抖避免频繁请求
    $('#color-input', el).addEventListener('input', debounce((e) => {
      const hex = e.target.value || '';
      if (!hex) {
        return;
      }
      applyColor(hex, el);
    }, 300));

    // 容差滑块：仅在选择颜色后才会触发重新筛选
    $('#color-dist', el).addEventListener('input', (e) => {
      const v = Number(e.target.value);
      query.colorDistance = v;
      $('#color-dist-val', el).textContent = String(v);
      if (query.picColor) {
        reload(el);
      }
    });

    $('#color-clear', el).addEventListener('click', () => clearColor(el));

    // 颜色筛选：把已存在的查询态同步到控件，避免重新进入页面时控件与 query 不一致
    const colorInput = $('#color-input', el);
    if (query.picColor && colorInput) {
      colorInput.value = query.picColor;
    }
    syncColorControls(el);

    $('#upload-btn', el).addEventListener('click', () => {
      if (!state.isLogin()) {
        toast('请先登录后再上传影像', 'error');
        window.location.hash = '/login';
        return;
      }
      openUploadModal({ onSuccess: () => load(el) });
    });

    load(el);
  },
};
