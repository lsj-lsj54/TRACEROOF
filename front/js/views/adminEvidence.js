/**
 * 影像管理页（仅管理员）：审核、编辑、重新上传、删除
 */
import { evidenceApi } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime, formatSize, truncate, debounce, renderPager, renderEmpty, confirmDialog, reviewTag, showModal, closeModal, normalizePage, renderTableSkeleton,
} from '../ui.js';
import { openEditModal, openUploadModal, thumbUrl } from '../components/evidence.js';

const PAGE_SIZE = 10;

const query = {
  current: 1,
  pageSize: PAGE_SIZE,
  // 空字符串表示全部状态
  reviewStatus: '',
  searchText: '',
  sortField: 'createTime',
  sortOrder: 'descend',
};

/** 拉取影像分页数据（实体接口，能看到审核信息） */
async function load(el) {
  const tbody = $('#tbody', el);
  tbody.innerHTML = renderTableSkeleton(7, 5);
  try {
    const data = await evidenceApi.listByPage({ ...query });
    renderTable(el, normalizePage(data));
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="7" class="loading">${escapeHtml(e.message)}</td></tr>`;
  }
}

/** 删除影像 */
async function removeEvidence(el, id) {
  const confirmed = await confirmDialog('删除后无法恢复，确定删除这张影像吗？');
  if (!confirmed) {
    return;
  }
  try {
    await evidenceApi.delete(id);
    toast('删除成功', 'success');
    load(el);
  } catch (e) {
    toast(e.message, 'error');
  }
}

/** 打开审核弹窗 */
function openReviewModal(el, evidence) {
  const modal = showModal({
    title: `审核影像 · ${evidence.name}`,
    content: `
      <div class="review-preview">
        <img src="${escapeHtml(evidence.url)}" alt="${escapeHtml(evidence.name)}">
      </div>
      <form id="review-form" class="form">
        <div class="field">
          <span>审核结果</span>
          <div class="radio-row">
            <label><input type="radio" name="reviewStatus" value="1" checked> 通过</label>
            <label><input type="radio" name="reviewStatus" value="2"> 拒绝</label>
          </div>
        </div>
        <label class="field">
          <span>审核信息</span>
          <textarea name="reviewMessage" rows="3" placeholder="可选，例如：影像内容不符合规范"></textarea>
        </label>
        <p class="form-tip">
          分类：${escapeHtml(evidence.category) || '未分类'}
          · 上传者 id：${escapeHtml(evidence.userId)}
          · 状态：${reviewTag(evidence.reviewStatus)}
        </p>
        <div class="modal-foot">
          <button class="btn" type="button" data-modal-close>取消</button>
          <button class="btn btn-primary" type="submit">提交审核</button>
        </div>
      </form>`,
  });

  $('#review-form', modal).addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const reviewStatus = Number($('input[name="reviewStatus"]:checked', form).value);
    const reviewMessage = $('textarea[name="reviewMessage"]', form).value.trim();
    try {
      await evidenceApi.review({ id: evidence.id, reviewStatus, reviewMessage });
      closeModal();
      toast('审核完成', 'success');
      load(el);
    } catch (err) {
      toast(err.message, 'error');
    }
  });
}

/** 渲染表格 */
function renderTable(el, page) {
  const records = page.records || [];
  const tbody = $('#tbody', el);
  const pager = $('#pager', el);

  if (records.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7">${renderEmpty('没有符合条件的影像')}</td></tr>`;
    pager.innerHTML = '';
    return;
  }

  tbody.innerHTML = records.map((item) => `
    <tr data-id="${item.id}">
      <td>${item.id}</td>
      <td>
        <div class="cell-pic">
          <img src="${escapeHtml(thumbUrl(item))}" alt="${escapeHtml(item.name)}" loading="lazy">
          <span title="${escapeHtml(item.name)}">${escapeHtml(truncate(item.name, 16))}</span>
        </div>
      </td>
      <td>${escapeHtml(item.category) || '-'}</td>
      <td>${escapeHtml(item.userId)}</td>
      <td>${reviewTag(item.reviewStatus)}</td>
      <td>
        <div class="cell-sub">${formatSize(item.picSize)}</div>
        <div class="cell-sub">${formatTime(item.createTime)}</div>
      </td>
      <td class="cell-actions">
        ${item.reviewStatus === 0
    ? `<button class="btn btn-sm btn-primary" data-action="review" type="button">审核</button>`
    : `<button class="btn btn-sm" data-action="review" type="button">改判</button>`}
        <button class="btn btn-sm" data-action="edit" type="button">编辑</button>
        <button class="btn btn-sm" data-action="reupload" type="button">重传</button>
        <button class="btn btn-sm btn-danger" data-action="delete" type="button">删除</button>
      </td>
    </tr>`).join('');

  $$('[data-action]', tbody).forEach((btn) => {
    const row = btn.closest('tr');
    const id = row.dataset.id;
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      if (action === 'edit' || action === 'review') {
        const evidence = records.find((item) => String(item.id) === String(id));
        if (action === 'edit') {
          openEditModal({
            ...evidence,
            // 实体里的 tags 是 JSON 字符串，编辑弹窗需要数组
            tags: parseTags(evidence.tags),
          }, { onSuccess: () => load(el) });
        } else {
          openReviewModal(el, evidence);
        }
        return;
      }
      if (action === 'reupload') {
        const evidence = records.find((item) => String(item.id) === String(id));
        openUploadModal({
          evidenceId: id,
          archiveId: evidence.archiveId,
          onSuccess: () => load(el),
        });
        return;
      }
      if (action === 'delete') {
        removeEvidence(el, id);
      }
    });
  });

  pager.innerHTML = renderPager({
    current: page.current,
    pageSize: page.size,
    total: page.total,
  });
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
}

/** 实体里的 tags 是 JSON 字符串，转成数组给编辑弹窗用 */
function parseTags(tags) {
  if (!tags) {
    return [];
  }
  try {
    const parsed = JSON.parse(tags);
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    return [];
  }
}

export default {
  /**
   * 渲染影像管理页
   *
   * @param {HTMLElement} el 视图容器
   */
  render(el) {
    el.innerHTML = `
      <section class="toolbar">
        <div class="toolbar-left">
          <h2 class="page-title">影像管理</h2>
          <span class="page-sub">共 ${state.getCurrentUser().userName || '管理员'} 可审核全部影像</span>
        </div>
        <div class="toolbar-right">
          <button class="btn btn-primary" id="upload-btn" type="button">上传影像</button>
        </div>
      </section>
      <section class="filters">
        <input id="search-input" class="input" type="search" placeholder="搜索影像名称或简介"
               aria-label="搜索影像名称或简介" value="${escapeHtml(query.searchText)}">
        <select id="status-select" class="input input-select" aria-label="按审核状态筛选">
          <option value="" ${query.reviewStatus === '' ? 'selected' : ''}>全部状态</option>
          <option value="0" ${String(query.reviewStatus) === '0' ? 'selected' : ''}>待审核</option>
          <option value="1" ${String(query.reviewStatus) === '1' ? 'selected' : ''}>已通过</option>
          <option value="2" ${String(query.reviewStatus) === '2' ? 'selected' : ''}>已拒绝</option>
        </select>
      </section>
      <section class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>影像</th>
              <th>分类</th>
              <th>上传者</th>
              <th>审核状态</th>
              <th>信息</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody id="tbody"></tbody>
        </table>
      </section>
      <div id="pager"></div>`;

    $('#search-input', el).addEventListener('input', debounce((e) => {
      query.searchText = e.target.value.trim();
      query.current = 1;
      load(el);
    }, 400));

    $('#status-select', el).addEventListener('change', (e) => {
      query.reviewStatus = e.target.value;
      query.current = 1;
      load(el);
    });

    $('#upload-btn', el).addEventListener('click', () => {
      openUploadModal({ onSuccess: () => load(el) });
    });

    load(el);
  },
};
