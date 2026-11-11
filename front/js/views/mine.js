/**
 * 我的影像页：查看、编辑、重新上传、删除自己上传的影像
 */
import { evidenceApi } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime, formatSize,
  renderPager, renderEmpty, confirmDialog, REVIEW_STATUS, normalizePage,
  renderSkeleton, revealOnScroll, bindImageFade, attachRippleAll, attachRipple,
} from '../ui.js';
import {
  openEvidenceDetail, openEditModal, openUploadModal, thumbUrl,
} from '../components/evidence.js';
import { openNicknameModal } from '../components/profile.js';
import { openLightbox } from '../components/lightbox.js';
import { refreshShellUserName } from '../app.js';

/** 每页数量，后端限制不能超过 20 */
const PAGE_SIZE = 12;

const query = {
  current: 1,
  pageSize: PAGE_SIZE,
  sortField: 'createTime',
  sortOrder: 'descend',
};

/** 拉取当前登录用户的影像 */
async function load(el) {
  const grid = $('#grid', el);
  grid.innerHTML = renderSkeleton(PAGE_SIZE);
  try {
    const data = await evidenceApi.listVOByPage({
      ...query,
      userId: state.getCurrentUser().id,
    });
    renderGrid(el, normalizePage(data));
  } catch (e) {
    grid.innerHTML = renderEmpty(e.message);
  }
}

/** 删除影像 */
async function removeEvidence(el, id) {
  const confirmed = await confirmDialog('删除后无法恢复，确定要删除这张影像吗？');
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

/** 渲染卡片列表 */
function renderGrid(el, page) {
  const records = page.records || [];
  const grid = $('#grid', el);
  const pager = $('#pager', el);

  if (records.length === 0) {
    grid.innerHTML = renderEmpty('你还没有上传过影像，点右上角「上传影像」试试');
    pager.innerHTML = '';
    return;
  }

  grid.innerHTML = records.map((item) => {
    const review = REVIEW_STATUS[item.reviewStatus] || { text: '未知', cls: 'tag-pending' };
    return `
      <article class="card reveal" data-id="${item.id}">
        <div class="card-img">
          <img src="${escapeHtml(thumbUrl(item))}" alt="${escapeHtml(item.name)}" loading="lazy" data-fade>
          <span class="card-badge ${review.cls}">${review.text}</span>
        </div>
        <div class="card-body">
          <h3 class="card-title" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</h3>
          <p class="card-desc">${escapeHtml(item.introduction) || '暂无简介'}</p>
          <div class="card-meta">
            <span>${formatSize(item.picSize)}</span>
            <span>${formatTime(item.createTime)}</span>
          </div>
        </div>
        <div class="card-actions">
          <button class="btn btn-sm" data-action="detail" type="button">查看</button>
          <button class="btn btn-sm" data-action="edit" type="button">编辑</button>
          <button class="btn btn-sm" data-action="reupload" type="button">重传</button>
          <button class="btn btn-sm btn-danger" data-action="delete" type="button">删除</button>
        </div>
      </article>`;
  }).join('');

  $$('[data-action]', grid).forEach((btn) => {
    const id = btn.closest('.card').dataset.id;
    btn.addEventListener('click', async () => {
      const action = btn.dataset.action;
      if (action === 'detail') {
        openEvidenceDetail(id, { onChanged: () => load(el) });
        return;
      }
      if (action === 'edit') {
        // 详情接口能拿到完整的影像信息，直接复用
        try {
          const evidence = await evidenceApi.getVOById(id);
          openEditModal(evidence, { onSuccess: () => load(el) });
        } catch (e) {
          toast(e.message, 'error');
        }
        return;
      }
      if (action === 'reupload') {
        openUploadModal({ evidenceId: id, onSuccess: () => load(el) });
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

  // 点击缩略图全屏查看（与浏览页一致），详情仍走「查看」按钮
  $$('.card-img', grid).forEach((imgBox) => {
    imgBox.addEventListener('click', () => {
      const id = imgBox.closest('.card').dataset.id;
      const index = records.findIndex((item) => String(item.id) === String(id));
      openLightbox({ evidences: records, index: index < 0 ? 0 : index });
    });
  });

  bindImageFade(grid);
  revealOnScroll(grid);
  attachRippleAll(grid);
  $$('[data-action]', grid).forEach(attachRipple);
}

export default {
  /**
   * 渲染我的影像页
   *
   * @param {HTMLElement} el 视图容器
   */
  render(el) {
    const currentUser = state.getCurrentUser();
    const displayName = currentUser.userName || currentUser.userAccount;
    el.innerHTML = `
      <section class="toolbar">
        <div class="toolbar-left">
          <h2 class="page-title">我的影像</h2>
          <span class="page-sub">当前账号：<span id="mine-user-name">${escapeHtml(displayName)}</span>（这里只显示公共案例库的影像，档案库里的影像在「我的档案库」）</span>
        </div>
        <div class="toolbar-right">
          <button class="btn" id="nickname-btn" type="button">修改昵称</button>
          <button class="btn btn-primary" id="upload-btn" type="button">上传影像</button>
        </div>
      </section>
      <section id="grid" class="grid"></section>
      <div id="pager"></div>`;

    $('#upload-btn', el).addEventListener('click', () => {
      openUploadModal({ onSuccess: () => load(el) });
    });

    $('#nickname-btn', el).addEventListener('click', () => {
      openNicknameModal({
        onSuccess: (user) => {
          // 只改这一行文字，不必整页重渲染（重渲染会重新拉一遍影像列表）
          const nameEl = $('#mine-user-name', el);
          if (nameEl) {
            nameEl.textContent = user.userName || user.userAccount;
          }
          // 头部和这里是两处独立的 DOM，都要同步，否则会出现两个不同的昵称
          refreshShellUserName();
        },
      });
    });

    load(el);
  },
};
