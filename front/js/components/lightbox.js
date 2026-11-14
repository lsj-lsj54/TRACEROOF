/**
 * 全屏影像查看器（Lightbox）
 * <p>
 * 面向「看图」这件事本身：全屏沉浸、滚轮/双击缩放、拖拽平移、键盘操作、
 * 底部缩略图条快速跳转，以及原图下载入口。
 *
 * 键盘：Esc 关闭 / ← → 上一张下一张 / + - 缩放 / 0 实际大小 / f 适应窗口
 * 鼠标：滚轮缩放（以指针为中心）、双击放大、放大后拖拽平移
 *
 * 无障碍：role=dialog + aria-modal、打开时锁滚动并把焦点移入、关闭后焦点归位。
 */
import { $, $$, escapeHtml, formatSize, attachRipple } from '../ui.js';

/** 缩放范围（1 = 适应窗口） */
const MIN_SCALE = 1;
const MAX_SCALE = 6;

/** 每次按钮/键盘缩放的倍率 */
const ZOOM_STEP = 1.25;

/** 双击放大的目标倍率 */
const DOUBLE_CLICK_SCALE = 2;

/** 缩略图与原图的取址规则，与 components/evidence.js 保持一致（此处内联，避免模块循环依赖） */
function thumbOf(evidence) {
  if (!evidence) {
    return '';
  }
  return evidence.thumbnailUrl || evidence.url || '';
}

function originOf(evidence) {
  if (!evidence) {
    return '';
  }
  return evidence.url || evidence.thumbnailUrl || '';
}

/** 当前打开的查看器实例（同一时刻只允许一个） */
let instance = null;

/**
 * 打开全屏查看器
 *
 * @param {object}   options
 * @param {Array}    options.evidences 影像列表（用于左右切换与缩略图条）
 * @param {number}   [options.index]  初始展示第几张，默认 0
 * @returns {object|null} 查看器控制器；参数不合法时返回 null
 */
export function openLightbox({ evidences, index = 0 } = {}) {
  const list = (evidences || []).filter(Boolean);
  if (list.length === 0) {
    return null;
  }
  // 已经开着就先关掉，避免叠两个查看器
  closeLightbox(true);

  const root = document.getElementById('lightbox-root') || (() => {
    const box = document.createElement('div');
    box.id = 'lightbox-root';
    document.body.appendChild(box);
    return box;
  })();

  const state = {
    index: Math.min(Math.max(index, 0), list.length - 1),
    scale: MIN_SCALE,
    tx: 0,
    ty: 0,
    dragging: false,
    startX: 0,
    startY: 0,
    startTx: 0,
    startTy: 0,
    moved: false,
    closeTimer: null,
  };
  const lastFocus = document.activeElement || null;

  root.innerHTML = `
    <div class="lightbox" role="dialog" aria-modal="true" aria-label="影像查看器" tabindex="-1">
      <header class="lb-bar">
        <div class="lb-title">
          <span class="lb-title-name" id="lb-name"></span>
          <span class="lb-counter" id="lb-counter"></span>
        </div>
        <div class="lb-tools">
          <button class="lb-btn" data-lb="zoom-out" type="button" aria-label="缩小" title="缩小（-）">−</button>
          <span class="lb-zoom" id="lb-zoom" aria-live="polite">100%</span>
          <button class="lb-btn" data-lb="zoom-in" type="button" aria-label="放大" title="放大（+）">＋</button>
          <button class="lb-btn" data-lb="fit" type="button" title="适应窗口（f）">适应</button>
          <button class="lb-btn" data-lb="actual" type="button" title="实际大小（0）">1:1</button>
          <a class="lb-btn" id="lb-origin" href="#" target="_blank" rel="noopener" title="在新标签打开原图">原图</a>
          <button class="lb-btn" data-lb="close" type="button" aria-label="关闭" title="关闭（Esc）">✕</button>
        </div>
      </header>

      <div class="lb-stage" id="lb-stage">
        <button class="lb-nav lb-prev" data-lb="prev" type="button" aria-label="上一张">‹</button>
        <img class="lb-img" id="lb-img" alt="" draggable="false">
        <div class="lb-spinner" id="lb-spinner" role="status" aria-label="加载中"></div>
        <button class="lb-nav lb-next" data-lb="next" type="button" aria-label="下一张">›</button>
      </div>

      <footer class="lb-foot">
        <div class="lb-meta" id="lb-meta"></div>
        ${list.length > 1 ? '<div class="lb-strip" id="lb-strip"></div>' : ''}
        <div class="lb-hint">← → 切换 · 滚轮缩放 · 双击放大</div>
      </footer>
    </div>`;

  const box = $('.lightbox', root);
  const stage = $('#lb-stage', root);
  const img = $('#lb-img', root);
  const spinner = $('#lb-spinner', root);
  const zoomLabel = $('#lb-zoom', root);
  const counter = $('#lb-counter', root);
  const nameEl = $('#lb-name', root);
  const metaEl = $('#lb-meta', root);
  const originLink = $('#lb-origin', root);
  const strip = $('#lb-strip', root);

  /* ------------------------------ 渲染 ------------------------------ */

  /** 缩略图条（一次渲染，之后只更新选中态） */
  function renderStrip() {
    if (!strip) {
      return;
    }
    strip.innerHTML = list.map((item, i) => `
      <button class="lb-thumb ${i === state.index ? 'is-active' : ''}" data-index="${i}" type="button"
              aria-label="第 ${i + 1} 张：${escapeHtml(item.name || '')}">
        <img src="${escapeHtml(thumbOf(item))}" alt="" loading="lazy">
      </button>`).join('');
    $$('.lb-thumb', strip).forEach((btn) => {
      btn.addEventListener('click', () => show(Number(btn.dataset.index)));
      attachRipple(btn);
    });
  }

  /** 高亮当前缩略图并滚动到可见区域 */
  function syncStrip() {
    if (!strip) {
      return;
    }
    $$('.lb-thumb', strip).forEach((btn, i) => {
      const active = i === state.index;
      btn.classList.toggle('is-active', active);
      if (active && typeof btn.scrollIntoView === 'function') {
        btn.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
      }
    });
  }

  /** 信息条：尺寸 / 体积 / 上传者 */
  function renderMeta(item) {
    const parts = [];
    if (item.picWidth && item.picHeight) {
      parts.push(`${item.picWidth} × ${item.picHeight}`);
    }
    if (item.picSize) {
      parts.push(formatSize(item.picSize));
    }
    if (item.user) {
      parts.push(escapeHtml(item.user.userName || item.user.userAccount || ''));
    }
    metaEl.innerHTML = parts.join(' · ');
  }

  /** 更新缩放百分比与「放大后拖拽」的样式 */
  function syncZoom() {
    zoomLabel.textContent = `${Math.round(state.scale * 100)}%`;
    const zoomed = state.scale > MIN_SCALE + 0.001;
    stage.classList.toggle('is-zoomed', zoomed);
    if (!zoomed) {
      // 回到适应窗口时顺手复位位移，避免「看着是适应态却偏到一边」
      state.tx = 0;
      state.ty = 0;
    }
    img.style.transform = `translate(${state.tx}px, ${state.ty}px) scale(${state.scale})`;
  }

  /** 展示第 i 张 */
  function show(i) {
    state.index = (i + list.length) % list.length;
    const item = list[state.index];
    state.scale = MIN_SCALE;
    state.tx = 0;
    state.ty = 0;
    syncZoom();

    img.classList.add('is-loading');
    spinner.hidden = false;
    img.alt = item.name || '';
    nameEl.textContent = item.name || '未命名影像';
    counter.textContent = `${state.index + 1} / ${list.length}`;
    originLink.href = originOf(item);
    renderMeta(item);
    syncStrip();

    const step = () => {
      img.classList.remove('is-loading');
      spinner.hidden = true;
    };
    const fail = () => {
      img.classList.remove('is-loading');
      spinner.hidden = true;
      metaEl.innerHTML = '影像加载失败，可点右上角「原图」重试';
    };
    img.onload = step;
    img.onerror = fail;
    img.src = originOf(item);
    // 命中缓存的影像不会再触发 load，这里补一次判定
    if (img.complete) {
      step();
    }

    // 隐藏时先把按钮锁掉，键盘用户不会 tab 到无效按钮
    $('[data-lb="prev"]', root).disabled = list.length <= 1;
    $('[data-lb="next"]', root).disabled = list.length <= 1;
  }

  /* ------------------------------ 缩放与平移 ------------------------------ */

  /** 把位移限制在合理范围，避免放大后把影像拖出屏幕找不回来 */
  function clampTranslate() {
    const rect = typeof stage.getBoundingClientRect === 'function' ? stage.getBoundingClientRect() : null;
    if (!rect || !rect.width || !rect.height) {
      return;
    }
    const maxX = Math.max(0, ((state.scale - 1) * rect.width) / 2);
    const maxY = Math.max(0, ((state.scale - 1) * rect.height) / 2);
    state.tx = Math.min(maxX, Math.max(-maxX, state.tx));
    state.ty = Math.min(maxY, Math.max(-maxY, state.ty));
  }

  /**
   * 以某个屏幕坐标为锚点缩放（滚轮缩放的「跟随指针」手感）
   *
   * @param {number} nextScale 目标倍率
   * @param {number} [clientX]  锚点 X
   * @param {number} [clientY]  锚点 Y
   * @param {number} [maxScale] 本次缩放允许的最大倍率
   */
  function zoomTo(nextScale, clientX, clientY, maxScale = MAX_SCALE) {
    const scale = Math.min(maxScale, Math.max(MIN_SCALE, nextScale));
    if (Math.abs(scale - state.scale) < 0.001) {
      return;
    }
    const rect = typeof stage.getBoundingClientRect === 'function' ? stage.getBoundingClientRect() : null;
    if (rect && typeof clientX === 'number' && typeof clientY === 'number') {
      // 锚点相对舞台中心的坐标
      const cx = clientX - rect.left - rect.width / 2;
      const cy = clientY - rect.top - rect.height / 2;
      const k = scale / state.scale;
      state.tx = cx - (cx - state.tx) * k;
      state.ty = cy - (cy - state.ty) * k;
    }
    state.scale = scale;
    clampTranslate();
    syncZoom();
  }

  /** 实际大小：clientWidth/clientHeight 是适应窗口时的布局尺寸，不受 transform 影响 */
  function actualSize() {
    const widthScale = img.naturalWidth > 0 && img.clientWidth > 0
      ? img.naturalWidth / img.clientWidth
      : 0;
    const heightScale = img.naturalHeight > 0 && img.clientHeight > 0
      ? img.naturalHeight / img.clientHeight
      : 0;
    const scale = widthScale || heightScale;
    if (scale > 0) {
      // 超大原图的实际倍率可能超过普通缩放上限，1:1 不应被 MAX_SCALE 截断。
      zoomTo(scale, undefined, undefined, scale);
    }
  }

  /* ------------------------------ 事件 ------------------------------ */

  const onKeydown = (e) => {
    switch (e.key) {
      case 'Escape':
        e.preventDefault();
        e.stopImmediatePropagation();
        e.stopPropagation();
        closeLightbox();
        break;
      case 'ArrowLeft':
        e.preventDefault();
        show(state.index - 1);
        break;
      case 'ArrowRight':
        e.preventDefault();
        show(state.index + 1);
        break;
      case '+':
      case '=':
        e.preventDefault();
        zoomTo(state.scale * ZOOM_STEP);
        break;
      case '-':
      case '_':
        e.preventDefault();
        zoomTo(state.scale / ZOOM_STEP);
        break;
      case '0':
        e.preventDefault();
        actualSize();
        break;
      case 'f':
        e.preventDefault();
        zoomTo(1);
        break;
      case 'Tab': {
        // 焦点陷阱：Tab 只在查看器内部循环，容器本身持有焦点时也不能跳出。
        const items = $$('button:not([disabled]), a[href]', box);
        if (items.length === 0) {
          break;
        }
        const first = items[0];
        const last = items[items.length - 1];
        const active = document.activeElement;
        const outside = !box.contains(active);
        if (e.shiftKey && (active === first || active === box || outside)) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && (active === last || active === box || outside)) {
          e.preventDefault();
          first.focus();
        }
        break;
      }
      default:
        break;
    }
  };

  box.addEventListener('click', (e) => {
    const action = e.target.closest('[data-lb]');
    if (!action) {
      // 点空白处（不在影像上）也关闭，符合看图习惯
      if (e.target === stage) {
        closeLightbox();
      }
      return;
    }
    switch (action.dataset.lb) {
      case 'close':
        closeLightbox();
        break;
      case 'prev':
        show(state.index - 1);
        break;
      case 'next':
        show(state.index + 1);
        break;
      case 'zoom-in':
        zoomTo(state.scale * ZOOM_STEP);
        break;
      case 'zoom-out':
        zoomTo(state.scale / ZOOM_STEP);
        break;
      case 'fit':
        zoomTo(1);
        break;
      case 'actual':
        actualSize();
        break;
      default:
        break;
    }
  });

  // 滚轮缩放：以指针为中心，并阻止页面跟着滚
  stage.addEventListener('wheel', (e) => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
    zoomTo(state.scale * factor, e.clientX, e.clientY);
  }, { passive: false });

  // 双击在「适应窗口」与 2 倍之间切换
  img.addEventListener('dblclick', () => {
    zoomTo(state.scale > MIN_SCALE + 0.001 ? 1 : DOUBLE_CLICK_SCALE);
  });

  // 拖拽平移（Pointer 事件同时覆盖鼠标与触控）
  img.addEventListener('pointerdown', (e) => {
    if (state.scale <= MIN_SCALE + 0.001) {
      return;
    }
    state.dragging = true;
    state.moved = false;
    state.startX = e.clientX;
    state.startY = e.clientY;
    state.startTx = state.tx;
    state.startTy = state.ty;
    img.classList.add('is-grabbing');
    if (typeof img.setPointerCapture === 'function') {
      try {
        img.setPointerCapture(e.pointerId);
      } catch (err) {
        /* 某些环境不支持捕获，忽略即可 */
      }
    }
  });

  img.addEventListener('pointermove', (e) => {
    if (!state.dragging) {
      return;
    }
    const dx = e.clientX - state.startX;
    const dy = e.clientY - state.startY;
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
      state.moved = true;
    }
    state.tx = state.startTx + dx;
    state.ty = state.startTy + dy;
    clampTranslate();
    img.style.transform = `translate(${state.tx}px, ${state.ty}px) scale(${state.scale})`;
  });

  const endDrag = () => {
    state.dragging = false;
    img.classList.remove('is-grabbing');
  };
  img.addEventListener('pointerup', endDrag);
  img.addEventListener('pointercancel', endDrag);
  img.addEventListener('dragstart', (e) => e.preventDefault());

  document.addEventListener('keydown', onKeydown, true);
  // 底层 modal 的 Esc 监听可能先注册在 document 上，需在 window 捕获阶段先拦住 Esc。
  const onWindowKeydown = (e) => {
    if (e.key === 'Escape') {
      onKeydown(e);
    }
  };
  window.addEventListener('keydown', onWindowKeydown, true);

  /* ------------------------------ 打开与关闭 ------------------------------ */

  renderStrip();
  show(state.index);
  attachRipple($('[data-lb="close"]', root));

  const body = document.body;
  if (body && body.classList) {
    body.classList.add('no-scroll');
  }
  // 焦点移入查看器容器本身：读屏会播报对话框，同时避免一进来就给某个按钮套上焦点框
  if (typeof box.focus === 'function') {
    box.focus({ preventScroll: true });
  }

  instance = {
    close: () => closeLightbox(),
    next: () => show(state.index + 1),
    prev: () => show(state.index - 1),
    getIndex: () => state.index,
    _state: state,
    _root: root,
    _box: box,
    _img: img,
    _lastFocus: lastFocus,
    _onKeydown: onKeydown,
    _onWindowKeydown: onWindowKeydown,
    _closed: false,
  };
  return instance;
}

/**
 * 关闭全屏查看器
 *
 * @param {boolean} [immediate] 是否跳过退场动画（切换影像列表时用）
 */
export function closeLightbox(immediate = false) {
  const current = instance;
  const root = (current && current._root) || document.getElementById('lightbox-root');
  if (!root) {
    instance = null;
    return;
  }
  if (current && current._closed && !immediate) {
    return;
  }
  const box = (current && current._box) || $('.lightbox', root);
  const state = current && current._state;
  if (current) {
    current._closed = true;
    document.removeEventListener('keydown', current._onKeydown, true);
    window.removeEventListener('keydown', current._onWindowKeydown, true);
    current._img.onload = null;
    current._img.onerror = null;
    if (state.closeTimer !== null) {
      clearTimeout(state.closeTimer);
      state.closeTimer = null;
    }
  }

  let finished = false;
  const finish = () => {
    if (finished) {
      return;
    }
    finished = true;
    // 延迟回调只能清理自己这一层，不能误清随后打开的新查看器。
    if (current && instance !== current) {
      return;
    }
    if (!current || $('.lightbox', root) === box) {
      root.innerHTML = '';
    }
    if (instance === current) {
      instance = null;
    }
    const body = document.body;
    // 还有弹窗时不能解锁，否则背景会跟着滚
    if (body && body.classList && !$('.modal-mask')) {
      body.classList.remove('no-scroll');
    }
    if (current && current._lastFocus && typeof current._lastFocus.focus === 'function') {
      try {
        current._lastFocus.focus({ preventScroll: true });
      } catch (e) {
        /* 元素可能已被移除，忽略 */
      }
    }
  };
  if (!box || immediate) {
    finish();
    return;
  }
  box.classList.add('is-closing');
  if (state) {
    state.closeTimer = setTimeout(() => {
      state.closeTimer = null;
      finish();
    }, 200);
  } else {
    setTimeout(finish, 200);
  }
}

/** 查看器是否处于打开状态 */
export function isLightboxOpen() {
  return !!instance;
}
