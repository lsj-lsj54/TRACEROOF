/**
 * 我的档案库页：档案库列表、创建、重命名、删除、进入档案库
 * <p>
 * 档案库分为私有档案库（archiveType=0）和团队档案库（archiveType=1）：
 * - 每个用户最多只能创建一个私有档案库和一个团队档案库；
 * - 团队档案库可以邀请其他成员协作，成员按角色获得权限；
 * - 我加入的团队档案库单独列在下方，与「我创建的档案库」区分开。
 */
import { archiveApi, archiveUserApi } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime, renderPager, renderEmpty,
  confirmDialog, showModal, closeModal, getFormValues, archiveLevelInfo,
  archiveLevelOptions, archiveTypeInfo, archiveRoleInfo, renderArchiveUsage, normalizePage,
  renderSkeleton, revealOnScroll, attachRippleAll,
} from '../ui.js';
import { openArchiveMemberModal } from '../components/archiveMember.js';
import { navigate } from '../app.js';

/** 每页数量 */
const PAGE_SIZE = 12;

const query = {
  current: 1,
  pageSize: PAGE_SIZE,
  sortField: 'createTime',
  sortOrder: 'descend',
};

/** 拉取当前用户自己创建的档案库 */
async function load(el) {
  const grid = $('#grid', el);
  grid.innerHTML = renderSkeleton(PAGE_SIZE);
  try {
    const data = await archiveApi.myListVOByPage({ ...query });
    renderGrid(el, normalizePage(data));
  } catch (e) {
    grid.innerHTML = renderEmpty(e.message);
  }
}

/** 渲染档案库卡片 */
function renderGrid(el, page) {
  const records = page.records || [];
  const grid = $('#grid', el);
  const pager = $('#pager', el);

  if (records.length === 0) {
    grid.innerHTML = renderEmpty('还没有档案库，点右上角「创建档案库」开始整理你的影像');
    pager.innerHTML = '';
    return;
  }

  grid.innerHTML = records.map((item) => {
    const level = archiveLevelInfo(item.archiveLevel);
    const type = archiveTypeInfo(item.archiveType);
    const isTeam = Number(item.archiveType) === 1;
    return `
      <article class="archive-card reveal" data-id="${item.id}" data-type="${item.archiveType || 0}">
        <div class="archive-card-head">
          <h3 class="archive-name" title="${escapeHtml(item.archiveName)}">${escapeHtml(item.archiveName)}</h3>
          <div class="archive-tags">
            <span class="tag ${level.cls}">${level.text}</span>
            <span class="tag ${type.cls}">${type.text}</span>
          </div>
        </div>
        ${renderArchiveUsage(item)}
        <div class="archive-card-meta">
          <span>创建于 ${formatTime(item.createTime)}</span>
        </div>
        <div class="card-actions">
          <button class="btn btn-sm btn-primary" data-action="open" type="button">进入档案库</button>
          ${isTeam ? '<button class="btn btn-sm" data-action="members" type="button">成员</button>' : ''}
          <button class="btn btn-sm" data-action="edit" type="button">重命名</button>
          <button class="btn btn-sm btn-danger" data-action="delete" type="button">删除</button>
        </div>
      </article>`;
  }).join('');

  $$('[data-action]', grid).forEach((btn) => {
    const card = btn.closest('.archive-card');
    const id = card.dataset.id;
    const archive = records.find((item) => String(item.id) === String(id));
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      if (action === 'open') {
        navigate('/archive/detail', { id });
        return;
      }
      if (action === 'edit') {
        openEditModal(el, archive);
        return;
      }
      if (action === 'members') {
        openArchiveMemberModal(archive, { onChanged: () => load(el) });
        return;
      }
      if (action === 'delete') {
        removeArchive(el, archive);
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

  revealOnScroll(grid);
  attachRippleAll(grid);
}

/** 创建档案库弹窗 */
function openCreateModal(el) {
  // 普通用户只能创建普通版档案库，后端也会再校验一次
  const onlyCommon = !state.isAdmin();
  const modal = showModal({
    title: '创建档案库',
    content: `
      <form id="archive-form" class="form">
        <label class="field">
          <span>档案库名称</span>
          <input name="archiveName" type="text" maxlength="30" required
                 placeholder="例如：我的旅行照片" autocomplete="off">
        </label>
        <label class="field">
          <span>档案库类型</span>
          <select name="archiveType">
            <option value="0">私有档案库（仅自己可见）</option>
            <option value="1">团队档案库（可邀请成员协作）</option>
          </select>
        </label>
        <label class="field">
          <span>档案库级别</span>
          <select name="archiveLevel">${archiveLevelOptions(0, onlyCommon)}</select>
        </label>
        <p class="form-tip" id="archive-tip">
          每个用户最多创建一个私有档案库和一个团队档案库；普通用户只能创建普通版。
        </p>
        <div class="modal-foot">
          <button class="btn" type="button" data-modal-close>取消</button>
          <button class="btn btn-primary" type="submit">创建</button>
        </div>
      </form>`,
  });

  $('#archive-form', modal).addEventListener('submit', async (e) => {
    e.preventDefault();
    const values = getFormValues(e.target);
    try {
      await archiveApi.add({
        archiveName: values.archiveName,
        archiveType: Number(values.archiveType) || 0,
        archiveLevel: Number(values.archiveLevel) || 0,
      });
      closeModal();
      toast('档案库创建成功', 'success');
      query.current = 1;
      load(el);
    } catch (err) {
      toast(err.message, 'error');
    }
  });
}

/** 重命名弹窗 */
function openEditModal(el, archive) {
  const modal = showModal({
    title: '重命名档案库',
    content: `
      <form id="archive-edit-form" class="form">
        <label class="field">
          <span>档案库名称</span>
          <input name="archiveName" type="text" maxlength="30" required
                 value="${escapeHtml(archive.archiveName)}" autocomplete="off">
        </label>
        <div class="modal-foot">
          <button class="btn" type="button" data-modal-close>取消</button>
          <button class="btn btn-primary" type="submit">保存</button>
        </div>
      </form>`,
  });

  $('#archive-edit-form', modal).addEventListener('submit', async (e) => {
    e.preventDefault();
    const values = getFormValues(e.target);
    try {
      await archiveApi.edit({ id: archive.id, archiveName: values.archiveName });
      closeModal();
      toast('保存成功', 'success');
      load(el);
    } catch (err) {
      toast(err.message, 'error');
    }
  });
}

/** 删除档案库 */
async function removeArchive(el, archive) {
  const confirmed = await confirmDialog(
    `删除档案库「${archive.archiveName}」会同时删除档案库内的 ${Number(archive.totalCount) || 0} 张影像，且无法恢复，确定继续吗？`,
    '删除档案库',
  );
  if (!confirmed) {
    return;
  }
  try {
    await archiveApi.delete(archive.id);
    toast('删除成功', 'success');
    load(el);
  } catch (e) {
    toast(e.message, 'error');
  }
}

/** 加载并渲染「我加入的团队档案库」 */
async function loadJoined(el) {
  const container = $('#joined-section', el);
  if (!container) return;
  container.innerHTML = `<div class="loading">加载中…</div>`;
  try {
    const data = await archiveUserApi.listMy();
    renderJoined(container, data || []);
  } catch (e) {
    container.innerHTML = renderEmpty(e.message);
  }
}

/** 渲染我加入的团队档案库 */
function renderJoined(container, list) {
  if (list.length === 0) {
    container.innerHTML = renderEmpty('还没有加入任何团队档案库');
    return;
  }
  container.innerHTML = list.map((item) => {
    const archive = item.archive || {};
    const role = item.archiveRole ? archiveRoleInfo(item.archiveRole) : { text: '成员', cls: 'tag-viewer' };
    return `
      <article class="archive-card reveal joined-card" data-id="${archive.id}">
        <div class="archive-card-head">
          <h3 class="archive-name" title="${escapeHtml(archive.archiveName || '')}">${escapeHtml(archive.archiveName || '')}</h3>
          <span class="tag ${role.cls}">我的角色：${role.text}</span>
        </div>
        ${renderArchiveUsage(archive)}
        <div class="archive-card-meta">
          <span>创建于 ${formatTime(archive.createTime)}</span>
        </div>
        <div class="card-actions">
          <button class="btn btn-sm btn-primary" data-action="open" type="button">进入档案库</button>
          <button class="btn btn-sm btn-danger" data-action="quit" type="button">退出团队</button>
        </div>
      </article>`;
  }).join('');

  $$('[data-action]', container).forEach((btn) => {
    const card = btn.closest('.joined-card');
    const id = card.dataset.id;
    const item = list.find((i) => String(i.archive?.id || i.archiveId) === String(id));
    btn.addEventListener('click', () => {
      const action = btn.dataset.action;
      if (action === 'open') {
        navigate('/archive/detail', { id });
        return;
      }
      if (action === 'quit') {
        quitTeam(item, container);
      }
    });
  });

  revealOnScroll(container);
  attachRippleAll(container);
}

/** 退出团队 */
async function quitTeam(item, container) {
  if (!item || !item.id) {
    toast('缺少成员记录', 'error');
    return;
  }
  const confirmed = await confirmDialog('退出团队后将无法查看该档案库的影像，确定继续吗？', '退出团队');
  if (!confirmed) return;
  try {
    await archiveUserApi.delete(item.id);
    toast('已退出团队', 'success');
    loadJoined(container.closest('#view-archive'));
  } catch (e) {
    toast(e.message, 'error');
  }
}

export default {
  /**
   * 渲染我的档案库页
   *
   * @param {HTMLElement} el 视图容器
   */
  render(el) {
    el.innerHTML = `
      <section class="toolbar">
        <div class="toolbar-left">
          <h2 class="page-title">我的档案库</h2>
          <span class="page-sub">把影像按主题分档案库管理，团队档案库可邀请成员协作</span>
        </div>
        <div class="toolbar-right">
          <button class="btn btn-primary" id="create-archive-btn" type="button">创建档案库</button>
        </div>
      </section>
      <h3 class="section-title">我创建的档案库</h3>
      <section id="grid" class="archive-grid"></section>
      <div id="pager"></div>
      <h3 class="section-title">我加入的团队档案库</h3>
      <section id="joined-section" class="archive-grid"></section>`;

    $('#create-archive-btn', el).addEventListener('click', () => openCreateModal(el));

    load(el);
    loadJoined(el);
  },
};
