/**
 * 档案库详情页：查看某个档案库的信息与其中的影像，支持上传、编辑、重新上传、删除
 * <p>
 * 私有档案库：仅创建者（或管理员）可进入与操作；
 * 团队档案库（archiveType=1）：成员按角色获得权限，viewer 只能查看，editor/admin 可上传/编辑/删除。
 */
import { archiveApi, evidenceApi, archiveUserApi } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime, formatSize, renderPager, renderEmpty,
  confirmDialog, REVIEW_STATUS, archiveLevelInfo, archiveTypeInfo, renderArchiveUsage,
  normalizePage, renderSkeleton, revealOnScroll, bindImageFade, attachRippleAll, attachRipple,
} from '../ui.js';
import { openEvidenceDetail, openEditModal, openUploadModal, thumbUrl } from '../components/evidence.js';
import { openLightbox } from '../components/lightbox.js';
import { openArchiveMemberModal } from '../components/archiveMember.js';
import { navigate } from '../app.js';

/** 每页数量，后端限制不能超过 20 */
const PAGE_SIZE = 12;

/** 当前档案库，render 时从路由参数写入 */
let currentArchive = null;

/** 当前登录用户在该团队档案库中的角色 */
let myArchiveRole = null;

const query = {
  current: 1,
  pageSize: PAGE_SIZE,
  sortField: 'createTime',
  sortOrder: 'descend',
};

/** 当前用户是否为档案库创建者 */
function isCreator() {
  if (!state.isLogin() || !currentArchive) return false;
  return String(currentArchive.userId) === String(state.currentUser.id);
}

/** 当前用户是否有档案库内影像的操作权限（上传/编辑/删除） */
function canOperateInArchive() {
  if (isCreator() || state.isAdmin()) return true;
  if (Number(currentArchive.archiveType) !== 1) return false;
  return myArchiveRole === 'admin' || myArchiveRole === 'editor';
}

/** 渲染档案库信息（名称、级别、类型、用量） */
function renderArchiveInfo(el, archive) {
  const info = $('#archive-info', el);
  if (!info) return;
  const level = archiveLevelInfo(archive.archiveLevel);
  const type = archiveTypeInfo(archive.archiveType);
  const isTeam = Number(archive.archiveType) === 1;
  const memberBtn = isTeam
    ? `<button class="btn btn-sm" id="member-manage-btn" type="button">成员管理</button>`
    : '';
  info.innerHTML = `
    <div class="archive-detail-head">
      <h2 class="page-title">${escapeHtml(archive.archiveName)}</h2>
      <div class="archive-tags">
        <span class="tag ${level.cls}">${level.text}</span>
        <span class="tag ${type.cls}">${type.text}</span>
      </div>
      ${memberBtn}
      <a class="btn btn-sm" href="#/analyze?archiveId=${archive.id}">查看分析</a>
    </div>
    ${renderArchiveUsage(archive)}`;
  if (isTeam) {
    $('#member-manage-btn', info)?.addEventListener('click', () => {
      openArchiveMemberModal(currentArchive, { onChanged: () => initMyRole(el) });
    });
  }
}

/** 初始化当前用户在团队档案库中的角色（私有档案库不查） */
async function initMyRole(el) {
  myArchiveRole = null;
  if (!currentArchive || Number(currentArchive.archiveType) !== 1) return;
  if (isCreator()) {
    myArchiveRole = 'admin';
    return;
  }
  if (!state.isLogin()) return;
  try {
    const list = await archiveUserApi.list({ archiveId: currentArchive.id });
    const me = list.find((m) => String(m.userId) === String(state.currentUser.id));
    myArchiveRole = me ? me.archiveRole : null;
    updateUploadButton(el);
  } catch (e) {
    // 静默失败：后端会在操作时再校验，无角色则不展示操作按钮
  }
}

/** 根据角色控制「上传影像」按钮显隐 */
function updateUploadButton(el) {
  const btn = $('#upload-btn', el);
  if (!btn) return;
  btn.style.display = canOperateInArchive() ? '' : 'none';
}

/** 重新加载档案库信息：上传、删除后用量会变，必须刷新进度条 */
async function loadArchive(el) {
  try {
    currentArchive = await archiveApi.getVOById(currentArchive.id);
    renderArchiveInfo(el, currentArchive);
    await initMyRole(el);
  } catch (e) {
    $('#archive-info', el).innerHTML = renderEmpty(e.message);
  }
}

/** 加载档案库内的影像 */
async function loadEvidences(el) {
  const grid = $('#grid', el);
  grid.innerHTML = renderSkeleton(PAGE_SIZE);
  try {
    const data = await evidenceApi.listVOByPage({ ...query, archiveId: currentArchive.id });
    renderGrid(el, normalizePage(data));
  } catch (e) {
    grid.innerHTML = renderEmpty(e.message);
  }
}

/** 上传成功或删除后：用量和列表都要刷新 */
function reload(el) {
  loadArchive(el);
  loadEvidences(el);
}

/** 删除档案库内的影像 */
async function removeEvidence(el, id) {
  const confirmed = await confirmDialog('删除后无法恢复，确定要删除这张影像吗？');
  if (!confirmed) return;
  try {
    await evidenceApi.delete(id);
    toast('删除成功', 'success');
    reload(el);
  } catch (e) {
    toast(e.message, 'error');
  }
}

/** 渲染影像卡片 */
function renderGrid(el, page) {
  const records = page.records || [];
  const grid = $('#grid', el);
  const pager = $('#pager', el);
  const operable = canOperateInArchive();

  if (records.length === 0) {
    grid.innerHTML = renderEmpty('这个档案库还没有影像，点右上角「上传影像」放几张进来');
    pager.innerHTML = '';
    return;
  }

  grid.innerHTML = records.map((item) => {
    const review = REVIEW_STATUS[item.reviewStatus] || { text: '未知', cls: 'tag-pending' };
    const actionBtns = operable
      ? `
          <button class="btn btn-sm" data-action="edit" type="button">编辑</button>
          <button class="btn btn-sm" data-action="reupload" type="button">重传</button>
          <button class="btn btn-sm btn-danger" data-action="delete" type="button">删除</button>`
      : '';
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
          ${actionBtns}
        </div>
      </article>`;
  }).join('');

  $$('[data-action]', grid).forEach((btn) => {
    const id = btn.closest('.card').dataset.id;
    btn.addEventListener('click', async () => {
      const action = btn.dataset.action;
      if (action === 'detail') {
        openEvidenceDetail(id, { onChanged: () => reload(el) });
        return;
      }
      if (action === 'edit') {
        try {
          const evidence = await evidenceApi.getVOById(id);
          openEditModal(evidence, { onSuccess: () => reload(el) });
        } catch (e) {
          toast(e.message, 'error');
        }
        return;
      }
      if (action === 'reupload') {
        openUploadModal({
          evidenceId: id,
          archiveId: currentArchive.id,
          archiveName: currentArchive.archiveName,
          onSuccess: () => reload(el),
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
      if (target < 1) return;
      query.current = target;
      loadEvidences(el);
    });
  });

  // 点击缩略图全屏查看
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
   * 渲染档案库详情页
   *
   * @param {HTMLElement} el     视图容器
   * @param {object}      params 路由参数，params.id 为档案库 id
   */
  render(el, params = {}) {
    const archiveId = params.id;
    if (!archiveId) {
      el.innerHTML = `
        ${renderEmpty('缺少档案库 id')}
        <div class="page-actions"><a class="btn btn-sm" href="#/archive">返回我的档案库</a></div>`;
      return;
    }
    currentArchive = { id: archiveId };
    myArchiveRole = null;
    query.current = 1;

    el.innerHTML = `
      <section class="toolbar">
        <div class="toolbar-left">
          <button class="btn btn-sm" id="back-btn" type="button">← 我的档案库</button>
          <div id="archive-info" class="archive-info"><div class="loading">加载中…</div></div>
        </div>
        <div class="toolbar-right">
          <button class="btn btn-primary" id="upload-btn" type="button">上传影像</button>
        </div>
      </section>
      <section id="grid" class="grid"></section>
      <div id="pager"></div>`;

    $('#back-btn', el).addEventListener('click', () => navigate('/archive'));
    $('#upload-btn', el).addEventListener('click', () => {
      openUploadModal({
        archiveId: currentArchive.id,
        archiveName: currentArchive.archiveName,
        onSuccess: () => reload(el),
      });
    });

    // 先拿档案库信息再查影像：拿不到（无权限/已删除）时只提示一次，不再发多余的请求
    archiveApi.getVOById(archiveId)
      .then((archive) => {
        currentArchive = archive;
        renderArchiveInfo(el, archive);
        updateUploadButton(el);
        return initMyRole(el).then(() => loadEvidences(el));
      })
      .catch((e) => {
        $('#archive-info', el).innerHTML = renderEmpty(`${e.message}（可能是档案库已被删除，或你没有权限）`);
        $('#grid', el).innerHTML = '';
      });
  },
};
