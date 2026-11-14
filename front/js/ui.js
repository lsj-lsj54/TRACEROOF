/**
 * 通用 UI 工具与全局状态
 */

/** 转义 HTML，所有渲染到页面的用户输入都要过一遍，避免 XSS */
export function escapeHtml(str) {
  if (str === null || str === undefined) {
    return '';
  }
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 简写 querySelector */
export const $ = (selector, root = document) => root.querySelector(selector);

/** 简写 querySelectorAll，返回真数组 */
export const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

/* ------------------------------ 全局状态 ------------------------------ */

export const state = {
  /** 当前登录用户，未登录为 null */
  currentUser: null,

  /** 获取当前登录用户 */
  getCurrentUser() {
    return this.currentUser;
  },

  /** 设置当前登录用户 */
  setCurrentUser(user) {
    this.currentUser = user || null;
  },

  /** 是否已登录 */
  isLogin() {
    return !!(this.currentUser && this.currentUser.id);
  },

  /** 是否是管理员 */
  isAdmin() {
    return this.isLogin() && this.currentUser.userRole === 'admin';
  },
};

/* ------------------------------ 常量 ------------------------------ */

/** 审核状态：0-待审核 1-通过 2-拒绝 */
export const REVIEW_STATUS = {
  0: { text: '待审核', cls: 'tag-pending' },
  1: { text: '通过', cls: 'tag-pass' },
  2: { text: '拒绝', cls: 'tag-reject' },
};

/** 影像分类 */
export const CATEGORY_OPTIONS = ['破损', '丢失', '污染', '延误', '错分', '其他'];

/** 档案库级别：0-普通版 1-专业版 2-旗舰版（与后端 ArchiveLevelEnum 一致） */
export const ARCHIVE_LEVEL = {
  0: { text: '普通版', cls: 'tag-common' },
  1: { text: '专业版', cls: 'tag-pro' },
  2: { text: '旗舰版', cls: 'tag-flagship' },
};

/**
 * 取档案库级别的展示信息
 *
 * @param {number} level 级别值
 * @returns {{text: string, cls: string}} 级别文案与样式类
 */
export function archiveLevelInfo(level) {
  return ARCHIVE_LEVEL[level] || { text: '未知级别', cls: 'tag-pending' };
}

/** 档案库类型：0-私有 1-团队（与后端 ArchiveTypeEnum 一致） */
export const ARCHIVE_TYPE = {
  0: { text: '私有', cls: 'tag-private' },
  1: { text: '团队', cls: 'tag-team' },
};

/**
 * 取档案库类型的展示信息
 *
 * @param {number} type 类型值，null/undefined 按私有处理（兼容老数据）
 * @returns {{text: string, cls: string}} 类型文案与样式类
 */
export function archiveTypeInfo(type) {
  return ARCHIVE_TYPE[type === null || type === undefined ? 0 : type]
    || { text: '未知类型', cls: 'tag-pending' };
}

/** 档案库角色（与后端 ArchiveRoleEnum 一致） */
export const ARCHIVE_ROLE = {
  viewer: { text: '浏览者', cls: 'tag-viewer' },
  editor: { text: '编辑者', cls: 'tag-editor' },
  admin: { text: '管理员', cls: 'tag-admin-role' },
};

/**
 * 取档案库角色的展示信息
 *
 * @param {string} role 角色值
 * @returns {{text: string, cls: string}} 角色文案与样式类
 */
export function archiveRoleInfo(role) {
  return ARCHIVE_ROLE[role] || { text: '未知角色', cls: 'tag-pending' };
}

/**
 * 档案库角色下拉选项
 *
 * @param {string} selected 当前选中值
 * @returns {string} option 列表 HTML
 */
export function archiveRoleOptions(selected = 'viewer') {
  return Object.keys(ARCHIVE_ROLE)
    .map((role) => `<option value="${role}" ${role === selected ? 'selected' : ''}>${ARCHIVE_ROLE[role].text}</option>`)
    .join('');
}

/* ------------------------------ 轻提示 ------------------------------ */

/** 各类型提示的图标，纯装饰（对读屏隐藏，语义靠 role 传达） */
const TOAST_ICON = { success: '✓', error: '!', info: 'i' };

/** 单条提示的存活时长（毫秒） */
const TOAST_DURATION = 2800;

/** 同时最多显示几条，超出时挤掉最早的 */
const TOAST_MAX = 4;

/** 安全移除节点：兼容没有 Element.remove 的环境 */
function detach(el) {
  if (!el) {
    return;
  }
  try {
    if (typeof el.remove === 'function') {
      el.remove();
      return;
    }
    if (el.parentNode) {
      el.parentNode.removeChild(el);
    }
  } catch (e) {
    /* 忽略：移除失败不影响主流程 */
  }
}

/**
 * 弹出轻提示（支持多条堆叠）
 *
 * @param {string} message 提示内容
 * @param {string} type    success | error | info
 */
export function toast(message, type = 'info') {
  const root = $('#toast-root');
  if (!root) {
    return;
  }
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  // 错误用 alert（读屏会立即打断播报），其余用 status
  el.setAttribute('role', type === 'error' ? 'alert' : 'status');
  if (el.style && typeof el.style.setProperty === 'function') {
    el.style.setProperty('--toast-duration', `${TOAST_DURATION}ms`);
  }
  el.innerHTML = `
    <span class="toast-icon" aria-hidden="true">${TOAST_ICON[type] || TOAST_ICON.info}</span>
    <span class="toast-message">${escapeHtml(message)}</span>
    <i class="toast-bar" aria-hidden="true"></i>`;

  let closed = false;
  const close = () => {
    if (closed) {
      return;
    }
    closed = true;
    el.classList.add('is-leaving');
    setTimeout(() => detach(el), 220);
  };

  // 点击可提前关闭（错误信息常常需要手动清掉）
  el.addEventListener('click', close);
  el.title = '点击关闭';

  root.appendChild(el);
  while (root.children.length > TOAST_MAX) {
    detach(root.children[0]);
  }
  setTimeout(close, TOAST_DURATION);
}

/* ------------------------------ 滚动锁定 ------------------------------ */

/** 锁页面滚动（弹窗 / 查看器打开时用，避免背景跟着滚） */
function lockScroll() {
  const body = document.body;
  if (body && body.classList) {
    body.classList.add('no-scroll');
  }
}

/** 解锁滚动：仍有弹窗或查看器时不解除 */
function unlockScroll() {
  if ($('.modal-mask') || $('.lightbox')) {
    return;
  }
  const body = document.body;
  if (body && body.classList) {
    body.classList.remove('no-scroll');
  }
}

/* ------------------------------ 弹窗 ------------------------------ */

/** 当前弹窗打开前的焦点元素，关闭后还回去（键盘用户的焦点不丢） */
let modalLastFocus = null;

/** 弹窗关闭的动画时长，与 CSS --dur-2 保持一致 */
const MODAL_EXIT_MS = 200;

/** 弹窗内可聚焦元素 */
function focusableIn(root) {
  return $$(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    root,
  ).filter((el) => !el.hidden);
}

/**
 * 打开弹窗
 *
 * @param {object} options
 * @param {string} options.title   标题
 * @param {string} options.content 内容 HTML
 * @param {string} [options.width] 宽度，如 '720px'
 * @returns {HTMLElement} 弹窗元素，方便继续绑定事件
 */
export function showModal({ title, content, width = '520px' }) {
  const root = $('#modal-root');
  // 键盘焦点还原点：只在没有弹窗时记录，避免弹窗套弹窗时记错
  if (!root.querySelector('.modal-mask')) {
    modalLastFocus = document.activeElement || null;
  }
  root.innerHTML = `
    <div class="modal-mask">
      <div class="modal" role="dialog" aria-modal="true" aria-label="${escapeHtml(title)}" style="width:${width}">
        <div class="modal-head">
          <h3>${escapeHtml(title)}</h3>
          <button class="icon-btn" data-modal-close type="button" aria-label="关闭">✕</button>
        </div>
        <div class="modal-body">${content}</div>
      </div>
    </div>`;
  const mask = $('.modal-mask', root);
  const modal = $('.modal', root);

  // 点遮罩关闭，点弹窗内部不关闭
  mask.addEventListener('click', (e) => {
    if (e.target === mask) {
      closeModal();
    }
  });
  $$('[data-modal-close]', root).forEach((btn) => {
    btn.addEventListener('click', closeModal);
  });

  // Esc 关闭 + Tab 焦点陷阱：键盘用户不会 tab 到背景页面
  const onKeydown = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      closeModal();
      return;
    }
    if (e.key !== 'Tab') {
      return;
    }
    const items = focusableIn(modal);
    if (items.length === 0) {
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !modal.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !modal.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  };
  document.addEventListener('keydown', onKeydown, true);
  modal._onKeydown = onKeydown;

  lockScroll();
  // 焦点移入弹窗，屏幕阅读器才会读到弹窗内容
  const autoFocus = focusableIn(modal).find((el) => !el.hasAttribute('data-modal-close'));
  if (autoFocus) {
    autoFocus.focus();
  } else {
    modal.setAttribute('tabindex', '-1');
    modal.focus();
  }
  return modal;
}

/**
 * 关闭弹窗（带退场动画）
 * <p>
 * 只有「当前这一层」才允许清空容器，避免关闭旧弹窗的延时回调把刚打开的新弹窗抹掉。
 */
export function closeModal() {
  const root = $('#modal-root');
  const mask = $('.modal-mask', root);
  if (!mask) {
    unlockScroll();
    return;
  }
  const modal = $('.modal', mask);
  if (modal && modal._onKeydown) {
    document.removeEventListener('keydown', modal._onKeydown, true);
  }
  mask.classList.add('is-closing');
  setTimeout(() => {
    if ($('.modal-mask', root) === mask) {
      root.innerHTML = '';
      // 焦点还给打开弹窗的那个元素
      if (modalLastFocus && typeof modalLastFocus.focus === 'function') {
        try {
          modalLastFocus.focus();
        } catch (e) {
          /* 元素可能已被移除，忽略 */
        }
      }
      modalLastFocus = null;
    }
    unlockScroll();
  }, MODAL_EXIT_MS);
}

/**
 * 确认框
 *
 * @param {string} message 提示内容
 * @param {string} [title] 标题
 * @returns {Promise<boolean>} 用户是否点了确认
 */
export function confirmDialog(message, title = '操作确认') {
  return new Promise((resolve) => {
    const modal = showModal({
      title,
      content: `
        <p class="confirm-text">${escapeHtml(message)}</p>
        <div class="modal-foot">
          <button class="btn" data-cancel type="button">取消</button>
          <button class="btn btn-danger" data-ok type="button">确定</button>
        </div>`,
    });
    const root = $('#modal-root');
    let settled = false;
    let observer = null;
    const settle = (value) => {
      if (settled) {
        return;
      }
      settled = true;
      if (observer) {
        observer.disconnect();
      }
      resolve(value);
    };
    // Esc、遮罩和右上角关闭都走 closeModal；弹窗真正移除时将确认框视为取消。
    observer = new MutationObserver(() => {
      if (!root.contains(modal)) {
        settle(false);
      }
    });
    observer.observe(root, { childList: true, subtree: true });
    $('[data-cancel]', modal).addEventListener('click', () => {
      closeModal();
      settle(false);
    });
    $('[data-ok]', modal).addEventListener('click', () => {
      closeModal();
      settle(true);
    });
  });
}

/* ------------------------------ 表单 ------------------------------ */

/**
 * 读取表单中的值
 *
 * @param {HTMLElement} form 表单元素
 * @returns {object} 表单数据
 */
export function getFormValues(form) {
  const values = {};
  $$('[name]', form).forEach((el) => {
    if (el.type === 'checkbox') {
      values[el.name] = el.checked;
      return;
    }
    // 密码不 trim：首尾空格也是密码的一部分。若注册时被悄悄去掉、
    // 登录时用户又照原样输入，就会出现「密码明明是对的却提示错误」。
    values[el.name] = el.type === 'password' ? el.value : el.value.trim();
  });
  return values;
}

/* ------------------------------ 格式化 ------------------------------ */

/**
 * 格式化时间
 *
 * @param {string|number} time 时间戳或时间字符串
 * @returns {string} yyyy-MM-dd HH:mm
 */
export function formatTime(time) {
  if (!time) {
    return '-';
  }
  const date = new Date(time);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 格式化文件体积
 *
 * @param {number} size 字节数
 * @returns {string} 可读体积
 */
export function formatSize(size) {
  if (size === null || size === undefined || size === '') {
    return '-';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(2)} MB`;
}

/**
 * 截断长文本
 *
 * @param {string} text  原文本
 * @param {number} len   最大长度
 */
export function truncate(text, len = 30) {
  if (!text) {
    return '';
  }
  return text.length > len ? `${text.slice(0, len)}…` : text;
}

/**
 * 防抖
 *
 * @param {Function} fn    目标函数
 * @param {number}   delay 延迟毫秒
 */
export function debounce(fn, delay = 400) {
  let timer = null;
  const debounced = (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      fn(...args);
    }, delay);
  };
  debounced.cancel = () => {
    clearTimeout(timer);
    timer = null;
  };
  return debounced;
}

/* ------------------------------ 片段 ------------------------------ */

/**
 * 审核状态标签
 *
 * @param {number} status 审核状态值
 */
export function reviewTag(status) {
  const item = REVIEW_STATUS[status] || { text: '未知', cls: 'tag-pending' };
  return `<span class="tag ${item.cls}">${item.text}</span>`;
}

/**
 * 归一化分页信息
 * <p>
 * 兜底逻辑：MyBatis-Plus 未注册分页插件时，后端既不会执行 count 也不会拼 limit，
 * 返回的是「全部记录 + total 恒为 0」。这里在 total 明显不合理时用记录数兜底，
 * 避免前端把翻页按钮全部禁掉、页面看起来空掉。
 *
 * @param {object} page 后端返回的分页对象
 * @returns {object} 归一化后的 { current, size, total, records }
 */
export function normalizePage(page) {
  const records = (page && page.records) || [];
  const size = Number(page && page.size) || records.length || 10;
  const current = Number(page && page.current) || 1;
  let total = Number(page && page.total);
  if (!Number.isFinite(total) || total < records.length) {
    // 至少要把已经拿到的记录数算进去
    total = Math.max(records.length, current * size);
  }
  return { current, size, total, records };
}

/** 分页组件 */
export function renderPager({ current, pageSize, total }) {
  const totalPage = Math.max(1, Math.ceil(total / pageSize));
  return `
    <div class="pager">
      <button class="btn btn-sm" data-page="${current - 1}" ${current <= 1 ? 'disabled' : ''}>上一页</button>
      <span class="pager-info">第 ${current} / ${totalPage} 页 · 共 ${total} 条</span>
      <button class="btn btn-sm" data-page="${current + 1}" ${current >= totalPage ? 'disabled' : ''}>下一页</button>
    </div>`;
}

/** 空状态 */
export function renderEmpty(text = '暂无数据') {
  return `<div class="empty">${escapeHtml(text)}</div>`;
}

/* ------------------------------ 档案库 ------------------------------ */

/**
 * 使用率百分比
 *
 * @param {number} used 已用量
 * @param {number} max  上限
 * @returns {number} 0 ~ 100 的整数，上限缺失时返回 0
 */
export function usagePercent(used, max) {
  const total = Number(max);
  const current = Number(used) || 0;
  if (!Number.isFinite(total) || total <= 0) {
    return 0;
  }
  return Math.min(100, Math.round((current / total) * 100));
}

/**
 * 档案库用量条（影像数量 + 已用容量）
 * <p>
 * 用量超过 90% 时进度条变红，提示用户该清理或升级档案库了。
 *
 * @param {object} archive 档案库对象
 * @returns {string} HTML 片段
 */
export function renderArchiveUsage(archive) {
  const countPercent = usagePercent(archive.totalCount, archive.maxCount);
  const sizePercent = usagePercent(archive.totalSize, archive.maxSize);
  return `
    <div class="usage">
      <div class="usage-row">
        <span>影像数量</span>
        <b>${Number(archive.totalCount) || 0} / ${Number(archive.maxCount) || 0}</b>
      </div>
      <div class="usage-track">
        <i class="usage-fill${countPercent >= 90 ? ' usage-full' : ''}" style="width:${countPercent}%"></i>
      </div>
      <div class="usage-row">
        <span>已用容量</span>
        <b>${formatSize(archive.totalSize)} / ${formatSize(archive.maxSize)}</b>
      </div>
      <div class="usage-track">
        <i class="usage-fill${sizePercent >= 90 ? ' usage-full' : ''}" style="width:${sizePercent}%"></i>
      </div>
    </div>`;
}

/**
 * 档案库级别下拉选项
 *
 * @param {number}  selected    当前选中值
 * @param {boolean} onlyCommon  是否只允许普通版（非管理员）
 * @returns {string} option 列表 HTML
 */
export function archiveLevelOptions(selected, onlyCommon = false) {
  return Object.keys(ARCHIVE_LEVEL)
    .map(Number)
    .filter((level) => !onlyCommon || level === 0)
    .map((level) => {
      const item = ARCHIVE_LEVEL[level];
      return `<option value="${level}" ${level === Number(selected) ? 'selected' : ''}>${item.text}</option>`;
    })
    .join('');
}

/* ------------------------------ 加载骨架 ------------------------------ */

/**
 * 骨架屏卡片占位
 * <p>
 * 列表加载期间铺一层与真实卡片同构的灰块，避免「一片空白 → 内容突然撑开」的
 * 视觉抖动，比单纯的「加载中…」文字更接近真实观感。
 *
 * @param {number} count 占位卡片数量
 * @returns {string} 骨架屏 HTML
 */
export function renderSkeleton(count = 8) {
  const card = `
    <div class="skeleton-card" aria-hidden="true">
      <div class="sk sk-img"></div>
      <div class="sk-body">
        <div class="sk sk-title"></div>
        <div class="sk sk-text"></div>
        <div class="sk sk-text-short"></div>
      </div>
    </div>`;
  const cards = new Array(Math.max(1, Math.min(count, 24))).fill(card).join('');
  return `<div class="skeleton-grid" role="status" aria-label="加载中">${cards}</div>`;
}

/* ------------------------------ 入场动效 ------------------------------ */

/**
 * 元素滚动进入视口时渐入（错峰）
 * <p>
 * 环境不支持 IntersectionObserver 时直接全部显示，不做任何降级视觉损失。
 *
 * @param {HTMLElement} root           容器
 * @param {object}      [options]
 * @param {string}      [options.selector] 选择器，默认 .reveal
 * @param {number}      [options.step]     每个元素的延迟步长（毫秒）
 * @returns {Function} 取消观察的函数
 */
export function revealOnScroll(root, { selector = '.reveal', step = 45 } = {}) {
  const items = $$(selector, root);
  if (items.length === 0) {
    return () => {};
  }
  items.forEach((el, index) => {
    if (el.style && typeof el.style.setProperty === 'function') {
      // 最多延迟 360ms，避免靠后的卡片等太久
      el.style.setProperty('--reveal-delay', `${Math.min(index * step, 360)}ms`);
    }
  });
  if (typeof IntersectionObserver === 'undefined') {
    items.forEach((el) => el.classList.add('is-in'));
    return () => {};
  }
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-in');
        observer.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -6% 0px', threshold: 0.04 });
  items.forEach((el) => observer.observe(el));
  return () => observer.disconnect();
}

/* ------------------------------ 影像淡入 ------------------------------ */

/**
 * 影像加载完成后再淡入
 * <p>
 * 只在 <img data-fade> 上生效，且加载失败也会显示出来：
 * 宁可露占位背景，也不能因为加载事件缺失让影像整块消失。
 *
 * @param {HTMLElement} root 容器
 */
export function bindImageFade(root) {
  $$('img[data-fade]', root).forEach((img) => {
    const show = () => {
      if (img.classList) {
        img.classList.add('is-loaded');
      }
    };
    if (img.complete) {
      show();
      return;
    }
    img.addEventListener('load', show, { once: true });
    img.addEventListener('error', show, { once: true });
  });
}

/* ------------------------------ 涟漪反馈 ------------------------------ */

/**
 * 给按钮加点击涟漪（从指针位置扩散）
 *
 * @param {HTMLElement} el 目标元素
 */
export function attachRipple(el) {
  if (!el || el._rippleBound) {
    return;
  }
  el._rippleBound = true;
  if (el.classList) {
    el.classList.add('ripple-host');
  }
  el.addEventListener('pointerdown', (e) => {
    const rect = typeof el.getBoundingClientRect === 'function' ? el.getBoundingClientRect() : null;
    if (!rect || !rect.width || typeof e.clientX !== 'number') {
      return;
    }
    const size = Math.max(rect.width, rect.height) * 2;
    const ink = document.createElement('span');
    ink.className = 'ripple-ink';
    ink.style.width = `${size}px`;
    ink.style.height = `${size}px`;
    ink.style.left = `${e.clientX - rect.left - size / 2}px`;
    ink.style.top = `${e.clientY - rect.top - size / 2}px`;
    el.appendChild(ink);
    setTimeout(() => detach(ink), 620);
  });
}

/**
 * 批量为容器内的按钮加涟漪
 *
 * @param {HTMLElement} root 容器
 */
export function attachRippleAll(root) {
  $$('.btn, .icon-btn, .lb-btn', root).forEach(attachRipple);
}

/**
 * 表格骨架行（管理端列表加载时占位）
 *
 * @param {number} cols 列数，需与真实表格一致
 * @param {number} rows 占位行数
 * @returns {string} tbody 内的占位 HTML
 */
export function renderTableSkeleton(cols = 6, rows = 5) {
  const cell = '<td><span class="sk" style="display:block;width:70%"></span></td>';
  return new Array(Math.max(1, Math.min(rows, 20)))
    .fill(`<tr class="skeleton-row" aria-hidden="true">${cell.repeat(Math.max(1, cols))}</tr>`)
    .join('');
}
