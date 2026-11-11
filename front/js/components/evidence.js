/**
 * 影像相关公共组件：上传弹窗、压缩、详情弹窗、编辑弹窗
 */
import { evidenceApi } from '../api.js';
import {
  $, $$, state, toast, showModal, closeModal, escapeHtml,
  getFormValues, formatTime, formatSize, CATEGORY_OPTIONS, debounce,
} from '../ui.js';
import { openLightbox } from './lightbox.js';
import { openCoEdit } from './coEdit.js';

/** 允许上传的后端格式，须与 FileManager.validEvidence 保持一致 */
const ALLOW_FORMAT = ['jpg', 'jpeg', 'png', 'webp'];

/** 后端单文件上限 2MB（FileManager 中是 2 * 1024 * 1024） */
const MAX_SIZE = 2 * 1024 * 1024;

/**
 * 前端压缩的触发体积
 * <p>
 * 实测后端 multipart 解析曾在 1MiB 处拒绝请求（已通过 application.yml 放开通到 10MB），
 * 这里仍保守地压在 900KB 以内，保证换任何环境都不会因为请求体过大而失败。
 */
const COMPRESS_TRIGGER = 900 * 1024;

/** 压缩后允许的最大体积 */
const COMPRESS_TARGET = 900 * 1024;

/** 像素数超过该值就压缩（约 1600 × 1600） */
const COMPRESS_EDGE = 1600;

/** 压缩后最长边 */
const COMPRESS_TARGET_EDGE = 1280;

/** 压缩质量候选，从高到低尝试，尽量保住画质 */
const QUALITY_STEPS = [0.9, 0.82, 0.72, 0.62, 0.5];

/** canvas 是否支持 webp 编码（webp 体积更小且支持透明） */
const SUPPORTS_WEBP = (() => {
  try {
    return document.createElement('canvas').toDataURL('image/webp').startsWith('data:image/webp');
  } catch (e) {
    return false;
  }
})();

/** 取文件后缀（小写） */
function getSuffix(filename) {
  const index = filename.lastIndexOf('.');
  return index === -1 ? '' : filename.slice(index + 1).toLowerCase();
}

/** 创建对象 URL 并在用完后释放 */
function releaseUrl(url) {
  if (url && url.startsWith('blob:')) {
    URL.revokeObjectURL(url);
  }
}

/** Promise 化的 canvas.toBlob */
function canvasToBlob(canvas, type, quality) {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality);
  });
}

/**
 * 压缩影像：缩到目标边长并逐档降低质量，直到体积达标
 * <p>
 * 同时支持两种编码格式，保证不丢透明：
 *  - webp：体积更小且支持透明，优先使用；
 *  - jpeg：浏览器不支持 webp 时兜底，先铺白底避免透明区域变黑。
 *
 * @param {File} file 原影像
 * @returns {Promise<File>} 压缩后的 File，无需压缩或压缩失败时返回原文件
 */
export function compressImage(file) {
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();

    image.onload = async () => {
      try {
        const { width, height } = image;
        const needCompress = file.size > COMPRESS_TRIGGER
          || Math.max(width, height) > COMPRESS_EDGE;
        if (!needCompress) {
          resolve(file);
          return;
        }

        const scale = Math.min(1, COMPRESS_TARGET_EDGE / Math.max(width, height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(width * scale));
        canvas.height = Math.max(1, Math.round(height * scale));
        const ctx = canvas.getContext('2d');

        const type = SUPPORTS_WEBP ? 'image/webp' : 'image/jpeg';
        const suffix = SUPPORTS_WEBP ? 'webp' : 'jpg';
        if (type === 'image/jpeg') {
          // jpeg 不支持透明，先铺白底，否则透明区域会变成黑色
          ctx.fillStyle = '#ffffff';
          ctx.fillRect(0, 0, canvas.width, canvas.height);
        }
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height);

        let best = null;
        for (const quality of QUALITY_STEPS) {
          const blob = await canvasToBlob(canvas, type, quality);
          if (!blob) {
            break;
          }
          best = blob;
          if (blob.size <= COMPRESS_TARGET) {
            break;
          }
        }

        if (!best) {
          resolve(file);
          return;
        }
        const name = file.name.replace(/\.[^.]+$/, '') + '.' + suffix;
        resolve(new File([best], name, { type }));
      } finally {
        releaseUrl(objectUrl);
      }
    };

    image.onerror = () => {
      releaseUrl(objectUrl);
      resolve(file);
    };
    image.src = objectUrl;
  });
}

/** 上传进度环的圆周长（r = 19），用于描边动画 */
const RING_CIRCUMFERENCE = 2 * Math.PI * 19;

/**
 * 上传进度环
 * <p>
 * fetch 拿不到真实上传进度，因此「上传中」用旋转弧表示进行中，
 * 完成后环变绿并弹出一个对勾——比「上传中…」三个字更有确定感。
 *
 * @param {string} stateClass is-indeterminate | is-done
 * @returns {string} HTML 片段
 */
function renderRing(stateClass) {
  return `
    <span class="upload-ring ${stateClass}" aria-hidden="true">
      <svg viewBox="0 0 44 44">
        <circle class="ring-bg" cx="22" cy="22" r="19"></circle>
        <circle class="ring-fg" cx="22" cy="22" r="19"
                stroke-dasharray="${RING_CIRCUMFERENCE.toFixed(1)}"
                stroke-dashoffset="0"></circle>
      </svg>
      ${stateClass === 'is-done' ? '<span class="upload-check">✓</span>' : ''}
    </span>`;
}

/**
 * 打开上传弹窗
 *
 * @param {object}   options
 * @param {string}   [options.evidenceId] 影像 id，传了表示覆盖上传
 * @param {string}   [options.archiveId]   档案库 id，缺省传 0 表示上传到公共案例库
 * @param {string}   [options.archiveName] 档案库名称，仅用于弹窗里的提示文案
 * @param {Function} [options.onSuccess] 上传成功回调
 */
export function openUploadModal({
  evidenceId, archiveId, archiveName, onSuccess,
} = {}) {
  const isOverwrite = !!evidenceId;
  const modal = showModal({
    title: isOverwrite ? '重新上传影像' : '上传影像',
    width: '640px',
    content: `
      ${archiveId ? `<p class="upload-archive-tip">将上传到档案库「${escapeHtml(archiveName || archiveId)}」</p>` : ''}
      <div class="tabs" id="upload-tabs">
        <button class="tab active" data-tab="file" type="button">本地文件</button>
        <button class="tab" data-tab="url" type="button">影像链接</button>
      </div>

      <section data-pane="file">
        <div class="upload-box" id="drop-area">
          <input type="file" id="file-input" accept="image/jpeg,image/png,image/webp"
                 ${isOverwrite ? '' : 'multiple'} hidden>
          <button class="btn btn-primary" type="button" id="pick-btn">选择影像</button>
          <p class="upload-tip">支持 jpg / jpeg / png / webp，单张不超过 2MB；过大的影像会在本地自动压缩${isOverwrite ? '' : '，可多选'}</p>
        </div>
        <div class="upload-list" id="file-list"></div>
      </section>

      <section data-pane="url" hidden>
        <label class="field">
          <span>影像地址</span>
          <input id="url-input" type="url" placeholder="https://example.com/evidence.jpg"
                 autocomplete="off">
        </label>
        <div class="url-preview" id="url-preview" hidden>
          <img id="url-preview-img" alt="预览">
        </div>
        <p class="upload-tip" id="url-hint">
          后端会把这个地址的影像下载后存入案例库，支持 http / https，单张不超过 2MB
        </p>
      </section>

      <div class="modal-foot">
        <button class="btn" type="button" data-modal-close>取消</button>
        <button class="btn btn-primary" id="start-upload" type="button" disabled>开始上传</button>
      </div>`,
  });

  const input = $('#file-input', modal);
  const listEl = $('#file-list', modal);
  const startBtn = $('#start-upload', modal);
  const dropArea = $('#drop-area', modal);
  const urlInput = $('#url-input', modal);
  const urlHint = $('#url-hint', modal);
  const urlPreview = $('#url-preview', modal);
  const urlPreviewImg = $('#url-preview-img', modal);
  const panes = {};
  $$('[data-pane]', modal).forEach((pane) => {
    panes[pane.dataset.pane] = pane;
  });

  /** 当前选中的上传方式：file / url */
  let mode = 'file';

  /** 待上传任务：{ file, preview, status, statusText } */
  let tasks = [];

  /** 设置 URL 输入框下方的提示文案 */
  function setUrlHint(text, isError = false) {
    urlHint.textContent = text;
    urlHint.classList.toggle('upload-tip-error', isError);
  }

  /** 切换上传方式 */
  function switchMode(next) {
    mode = next;
    Object.keys(panes).forEach((key) => {
      panes[key].hidden = key !== next;
    });
    $$('#upload-tabs .tab', modal).forEach((tab) => {
      tab.classList.toggle('active', tab.dataset.tab === next);
    });
    // 影像链接方式不支持「覆盖已有影像」，这与后端只能按 id 覆盖本地文件的实现一致
    startBtn.disabled = next === 'url'
      ? (isOverwrite || !urlInput.value.trim())
      : (tasks.length === 0 || tasks.some((task) => task.status === 'uploading'));
  }

  /** 重绘待上传列表 */
  function renderList() {
    if (tasks.length === 0) {
      listEl.innerHTML = '';
      startBtn.disabled = true;
      return;
    }
    listEl.innerHTML = tasks.map((task, index) => `
      <div class="upload-item ${task.status === 'done' ? 'is-done' : ''} ${task.status === 'failed' ? 'is-failed' : ''}">
        ${task.status === 'uploading' ? renderRing('is-indeterminate')
    : task.status === 'done' ? renderRing('is-done')
      : `<img src="${task.preview}" alt="预览">`}
        <div class="upload-item-main">
          <div class="upload-item-name" title="${escapeHtml(task.file.name)}">
            ${escapeHtml(task.file.name)}
          </div>
          <div class="upload-item-size">
            ${formatSize(task.file.size)}
            <span class="upload-status ${task.status}">${task.statusText}</span>
          </div>
        </div>
        <button class="icon-btn" data-remove="${index}" type="button"
          ${task.status === 'uploading' ? 'disabled' : ''}>✕</button>
      </div>`).join('');
    startBtn.disabled = tasks.some((task) => task.status === 'uploading') || tasks.length === 0;
    $$('[data-remove]', listEl).forEach((btn) => {
      btn.addEventListener('click', () => {
        const index = Number(btn.dataset.remove);
        releaseUrl(tasks[index].preview);
        tasks.splice(index, 1);
        renderList();
      });
    });
  }

  /** 接收文件，做格式校验后加入待上传列表 */
  async function addFiles(fileList) {
    const files = Array.from(fileList);
    if (files.length === 0) {
      return;
    }
    for (const file of files) {
      if (!ALLOW_FORMAT.includes(getSuffix(file.name))) {
        toast(`「${file.name}」格式不支持，仅支持 jpg / png / webp`, 'error');
        continue;
      }
      const compressed = await compressImage(file);
      // 压缩后仍超出体积上限的直接拦下，避免白白发一次请求
      if (compressed.size > MAX_SIZE) {
        toast(
          `「${file.name}」压缩后仍有 ${formatSize(compressed.size)}，超过单张 2MB 上限，请先裁剪或导出更小的影像`,
          'error',
        );
        continue;
      }
      tasks.push({
        file: compressed,
        preview: URL.createObjectURL(compressed),
        status: 'ready',
        statusText: '待上传',
      });
    }
    if (isOverwrite && tasks.length > 1) {
      // 覆盖上传只允许一张，保留最后选择的
      while (tasks.length > 1) {
        releaseUrl(tasks.shift().preview);
      }
    }
    renderList();
  }

  /** 校验 URL 输入，顺便更新预览 */
  function refreshUrlState() {
    const value = urlInput.value.trim();
    if (!value) {
      urlPreview.hidden = true;
      urlPreviewImg.removeAttribute('src');
      setUrlHint('后端会把这个地址的影像下载后存入案例库，支持 http / https，单张不超过 2MB');
      startBtn.disabled = true;
      return;
    }
    if (!/^https?:\/\//i.test(value)) {
      urlPreview.hidden = true;
      urlPreviewImg.removeAttribute('src');
      setUrlHint('地址需要以 http:// 或 https:// 开头', true);
      startBtn.disabled = true;
      return;
    }
    urlPreviewImg.src = value;
    urlPreview.hidden = false;
    setUrlHint('将从这个地址下载影像并存入案例库');
    startBtn.disabled = isOverwrite;
  }

  /* ------------------------------ 事件绑定 ------------------------------ */

  $$('#upload-tabs .tab', modal).forEach((tab) => {
    tab.addEventListener('click', () => switchMode(tab.dataset.tab));
  });

  $('#pick-btn', modal).addEventListener('click', () => input.click());
  input.addEventListener('change', () => {
    addFiles(input.files);
    input.value = '';
  });

  // 拖拽上传
  dropArea.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropArea.classList.add('dragging');
  });
  dropArea.addEventListener('dragleave', () => dropArea.classList.remove('dragging'));
  dropArea.addEventListener('drop', (e) => {
    e.preventDefault();
    dropArea.classList.remove('dragging');
    addFiles(e.dataTransfer.files);
  });

  urlInput.addEventListener('input', debounce(refreshUrlState, 400));
  urlPreviewImg.addEventListener('error', () => {
    urlPreview.hidden = true;
  });

  startBtn.addEventListener('click', async () => {
    // 方式一：通过 URL 上传
    if (mode === 'url') {
      const evidenceUrl = urlInput.value.trim();
      if (!evidenceUrl) {
        toast('请输入影像地址', 'error');
        return;
      }
      startBtn.disabled = true;
      startBtn.textContent = '上传中…';
      setUrlHint('正在下载并上传，请稍候…');
      try {
        await evidenceApi.uploadByUrl(evidenceUrl, evidenceId, archiveId);
        toast('上传成功', 'success');
        if (typeof onSuccess === 'function') {
          onSuccess();
        }
        closeModal();
      } catch (e) {
        setUrlHint(e.message, true);
        startBtn.disabled = false;
        startBtn.textContent = '开始上传';
      }
      return;
    }

    // 方式二：本地文件
    const pending = tasks.filter((task) => task.status !== 'done');
    if (pending.length === 0) {
      return;
    }
    startBtn.disabled = true;
    let success = 0;

    for (const task of pending) {
      task.status = 'uploading';
      task.statusText = '上传中…';
      renderList();
      try {
        await evidenceApi.upload(task.file, evidenceId, archiveId);
        task.status = 'done';
        task.statusText = '已完成';
        success += 1;
        releaseUrl(task.preview);
      } catch (e) {
        task.status = 'failed';
        task.statusText = e.message;
      }
      renderList();
    }

    if (success > 0) {
      toast(isOverwrite ? '重新上传成功' : `成功上传 ${success} 张影像`, 'success');
      if (typeof onSuccess === 'function') {
        onSuccess();
      }
      closeModal();
    } else {
      startBtn.disabled = false;
    }
  });

  // 覆盖已有影像时不能走 URL，直接提示并停在「本地文件」
  if (isOverwrite) {
    const urlTab = $('#upload-tabs .tab[data-tab="url"]', modal);
    urlTab.addEventListener('click', () => {
      toast('重新上传只支持本地文件，请选择影像文件', 'info');
    });
  }

  // 弹窗关闭时释放所有预览地址
  const observer = new MutationObserver(() => {
    if (!$('#upload-tabs')) {
      tasks.forEach((task) => releaseUrl(task.preview));
      tasks = [];
      observer.disconnect();
    }
  });
  observer.observe($('#modal-root'), { childList: true });
  return modal;
}

/** 当前用户是否可以编辑该影像（本人或管理员） */
function canEdit(evidence) {
  if (!state.isLogin() || !evidence) {
    return false;
  }
  return state.isAdmin() || String(evidence.userId) === String(state.getCurrentUser().id);
}

/**
 * 列表页展示用的缩略图地址
 * <p>
 * 优先用后端上传时生成的压缩图（webp），体积通常只有原图的一小部分。
 * 历史数据该字段为空，回退到原图，保证不会出现裂图。
 *
 * @param {object} evidence 影像对象
 * @returns {string} 可展示的影像地址
 */
export function thumbUrl(evidence) {
  if (!evidence) {
    return '';
  }
  return evidence.thumbnailUrl || evidence.url || '';
}

/**
 * 详情页展示用的原图地址
 *
 * @param {object} evidence 影像对象
 * @returns {string} 原图地址
 */
export function originUrl(evidence) {
  if (!evidence) {
    return '';
  }
  return evidence.url || evidence.thumbnailUrl || '';
}

/**
 * 影像详情弹窗
 *
 * @param {string|number} id      影像 id
 * @param {object}        options
 * @param {Function}      [options.onChanged] 编辑或重新上传成功后的回调
 */
export async function openEvidenceDetail(id, { onChanged } = {}) {
  let evidence;
  try {
    evidence = await evidenceApi.getVOById(id);
  } catch (e) {
    toast(e.message, 'error');
    return;
  }

  const editable = canEdit(evidence);
  const tags = (evidence.tags || []).map((tag) => `<span class="tag">${escapeHtml(tag)}</span>`).join(' ');
  const modal = showModal({
    title: evidence.name || '影像详情',
    width: '760px',
    content: `
      <div class="detail">
        <div class="detail-img" id="detail-img" role="button" tabindex="0"
             aria-label="全屏查看影像">
          <img src="${escapeHtml(originUrl(evidence))}" alt="${escapeHtml(evidence.name)}">
        </div>
        <div class="detail-info">
          <div class="detail-row"><span>简介</span><p>${escapeHtml(evidence.introduction) || '暂无简介'}</p></div>
          <div class="detail-row"><span>分类</span><p>${escapeHtml(evidence.category) || '未分类'}</p></div>
          <div class="detail-row"><span>标签</span><p>${tags || '无'}</p></div>
          <div class="detail-row"><span>尺寸</span><p>${evidence.picWidth || '-'} × ${evidence.picHeight || '-'}</p></div>
          <div class="detail-row"><span>缩略图</span><p>${formatSize(evidence.picSize)}
            （列表页加载的资源大小）</p></div>
          <div class="detail-row"><span>上传者</span><p>${escapeHtml(evidence.user
    ? evidence.user.userName || evidence.user.userAccount : '-')}</p></div>
          <div class="detail-row"><span>所属档案库</span><p>${evidence.archiveId
    ? `档案库（<a href="#/archive/detail?id=${encodeURIComponent(evidence.archiveId)}">去档案库查看</a>）`
    : '公共案例库'}</p></div>
          <div class="detail-row"><span>上传时间</span><p>${formatTime(evidence.createTime)}</p></div>
          <div class="detail-actions">
            <a class="btn btn-sm" href="${escapeHtml(originUrl(evidence))}" target="_blank" rel="noopener">查看原图</a>
            ${editable ? '<button class="btn btn-sm btn-primary" id="detail-edit" type="button">编辑信息</button>' : ''}
            ${editable ? '<button class="btn btn-sm" id="detail-reupload" type="button">重新上传</button>' : ''}
            ${editable ? '<button class="btn btn-sm" id="detail-coedit" type="button">在线编辑</button>' : ''}
          </div>
        </div>
      </div>`,
  });

  // 点击影像（或键盘回车/空格）进入全屏查看器，支持缩放与键盘操作；
  // 详情弹窗保持打开，关掉查看器即可回到详情
  const detailImg = $('#detail-img', modal);
  if (detailImg) {
    const openViewer = () => openLightbox({ evidences: [evidence], index: 0 });
    detailImg.addEventListener('click', openViewer);
    detailImg.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openViewer();
      }
    });
  }

  const editBtn = $('#detail-edit', modal);
  if (editBtn) {
    editBtn.addEventListener('click', () => {
      closeModal();
      openEditModal(evidence, { onSuccess: onChanged });
    });
  }
  const reuploadBtn = $('#detail-reupload', modal);
  if (reuploadBtn) {
    reuploadBtn.addEventListener('click', () => {
      closeModal();
      openUploadModal({
        evidenceId: evidence.id,
        // 带上原档案库：覆盖上传后影像仍归属原来的档案库
        archiveId: evidence.archiveId,
        onSuccess: onChanged,
      });
    });
  }
  const coeditBtn = $('#detail-coedit', modal);
  if (coeditBtn) {
    coeditBtn.addEventListener('click', () => {
      closeModal();
      openCoEdit(evidence);
    });
  }
}

/**
 * 编辑影像信息弹窗
 *
 * @param {object}   evidence             影像对象
 * @param {object}   options
 * @param {Function} [options.onSuccess] 保存成功回调
 */
export function openEditModal(evidence, { onSuccess } = {}) {
  const categoryOptions = CATEGORY_OPTIONS
    .map((item) => `<option value="${item}" ${item === evidence.category ? 'selected' : ''}>${item}</option>`)
    .join('');
  const modal = showModal({
    title: '编辑影像信息',
    content: `
      <form id="edit-form" class="form">
        <label class="field">
          <span>影像名称</span>
          <input name="name" type="text" value="${escapeHtml(evidence.name)}" maxlength="80" required>
        </label>
        <label class="field">
          <span>简介</span>
          <textarea name="introduction" rows="3" maxlength="800"
            placeholder="最多 800 字">${escapeHtml(evidence.introduction)}</textarea>
        </label>
        <label class="field">
          <span>分类</span>
          <select name="category">
            <option value="">未分类</option>
            ${categoryOptions}
          </select>
        </label>
        <label class="field">
          <span>标签</span>
          <input name="tags" type="text" placeholder="用英文逗号分隔，如：破损,外包装"
                 value="${escapeHtml((evidence.tags || []).join(','))}">
        </label>
        <div class="modal-foot">
          <button class="btn" type="button" data-modal-close>取消</button>
          <button class="btn btn-primary" type="submit">保存</button>
        </div>
      </form>`,
  });

  $('#edit-form', modal).addEventListener('submit', async (e) => {
    e.preventDefault();
    const values = getFormValues(e.target);
    const tags = values.tags
      ? values.tags.split(',').map((tag) => tag.trim()).filter(Boolean)
      : [];
    const payload = {
      id: evidence.id,
      name: values.name,
      introduction: values.introduction,
      category: values.category,
      tags,
    };
    try {
      // 管理员走 /evidence/update，普通用户走 /evidence/edit
      if (state.isAdmin()) {
        await evidenceApi.update(payload);
      } else {
        await evidenceApi.edit(payload);
      }
      closeModal();
      toast('保存成功', 'success');
      if (typeof onSuccess === 'function') {
        onSuccess();
      }
    } catch (err) {
      toast(err.message, 'error');
    }
  });
}
