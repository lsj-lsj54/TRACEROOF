/**
 * 用户管理页（仅管理员）：新增、查询、编辑、删除用户
 */
import { userApi } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime, debounce, renderPager, renderEmpty, confirmDialog, showModal, closeModal, getFormValues, normalizePage, renderTableSkeleton,
} from '../ui.js';

const PAGE_SIZE = 10;

const query = {
  current: 1,
  pageSize: PAGE_SIZE,
  searchText: '',
  userRole: '',
  sortField: 'createTime',
  sortOrder: 'descend',
};

/** 拉取用户分页数据 */
async function load(el) {
  const tbody = $('#tbody', el);
  tbody.innerHTML = renderTableSkeleton(6, 5);
  try {
    const data = await userApi.listVOByPage({
      ...query,
      // 后端支持模糊搜索的字段是 userName，这里复用搜索框
      userName: query.searchText,
    });
    renderTable(el, normalizePage(data));
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="6" class="loading">${escapeHtml(e.message)}</td></tr>`;
  }
}

/** 打开新增 / 编辑用户弹窗 */
function openUserModal(el, user) {
  const isEdit = !!user;
  const modal = showModal({
    title: isEdit ? `编辑用户 · ${user.userAccount}` : '新增用户',
    content: `
      <form id="user-form" class="form">
        ${isEdit ? '' : `
        <label class="field">
          <span>账号</span>
          <input name="userAccount" type="text" placeholder="至少 2 位" required>
        </label>`}
        <label class="field">
          <span>昵称</span>
          <input name="userName" type="text" value="${escapeHtml(isEdit ? user.userName : '')}" required>
        </label>
        <label class="field">
          <span>头像地址</span>
          <input name="userAvatar" type="text" placeholder="可选，影像 URL"
                 value="${escapeHtml(isEdit ? user.userAvatar : '')}">
        </label>
        <label class="field">
          <span>简介</span>
          <textarea name="userProfile" rows="2"
            placeholder="可选">${escapeHtml(isEdit ? user.userProfile : '')}</textarea>
        </label>
        <label class="field">
          <span>角色</span>
          <select name="userRole">
            <option value="user" ${isEdit && user.userRole === 'user' ? 'selected' : ''}>普通用户</option>
            <option value="admin" ${isEdit && user.userRole === 'admin' ? 'selected' : ''}>管理员</option>
          </select>
        </label>
        ${isEdit ? '' : '<p class="form-tip">默认密码为 12345678，创建后请提醒用户尽快修改</p>'}
        <div class="modal-foot">
          <button class="btn" type="button" data-modal-close>取消</button>
          <button class="btn btn-primary" type="submit">${isEdit ? '保存' : '创建'}</button>
        </div>
      </form>`,
  });

  $('#user-form', modal).addEventListener('submit', async (e) => {
    e.preventDefault();
    const values = getFormValues(e.target);
    try {
      if (isEdit) {
        await userApi.update({ ...values, id: user.id });
      } else {
        await userApi.add(values);
      }
      closeModal();
      toast(isEdit ? '保存成功' : '创建成功', 'success');
      load(el);
    } catch (err) {
      toast(err.message, 'error');
    }
  });
}

/** 删除用户 */
async function removeUser(el, user) {
  const confirmed = await confirmDialog(`确定删除用户「${user.userName || user.userAccount}」吗？`);
  if (!confirmed) {
    return;
  }
  try {
    await userApi.delete(user.id);
    toast('删除成功', 'success');
    load(el);
  } catch (e) {
    toast(e.message, 'error');
  }
}

/** 渲染表格 */
function renderTable(el, page) {
  const records = page.records || [];
  const tbody = $('#tbody', el);
  const pager = $('#pager', el);

  if (records.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6">${renderEmpty('没有符合条件的用户')}</td></tr>`;
    pager.innerHTML = '';
    return;
  }

  tbody.innerHTML = records.map((user) => `
    <tr data-id="${user.id}">
      <td>${user.id}</td>
      <td>
        <div class="cell-user">
          ${user.userAvatar
    ? `<img class="avatar" src="${escapeHtml(user.userAvatar)}" alt="头像">`
    : '<span class="avatar avatar-text">' + escapeHtml((user.userName || user.userAccount || '?').slice(0, 1)) + '</span>'}
          <div>
            <div>${escapeHtml(user.userName) || '-'}</div>
            <div class="cell-sub">${escapeHtml(user.userAccount)}</div>
          </div>
        </div>
      </td>
      <td>${escapeHtml(user.userProfile) || '-'}</td>
      <td>
        <span class="tag ${user.userRole === 'admin' ? 'tag-admin' : ''}">
          ${user.userRole === 'admin' ? '管理员' : '普通用户'}
        </span>
      </td>
      <td>${formatTime(user.createTime)}</td>
      <td class="cell-actions">
        <button class="btn btn-sm" data-action="edit" type="button">编辑</button>
        <button class="btn btn-sm btn-danger" data-action="delete" type="button"
          ${String(user.id) === String(state.getCurrentUser().id) ? 'disabled title="不能删除自己"' : ''}>删除</button>
      </td>
    </tr>`).join('');

  $$('[data-action]', tbody).forEach((btn) => {
    const id = btn.closest('tr').dataset.id;
    const user = records.find((item) => String(item.id) === String(id));
    btn.addEventListener('click', () => {
      if (btn.dataset.action === 'edit') {
        openUserModal(el, user);
      } else {
        removeUser(el, user);
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

export default {
  /**
   * 渲染用户管理页
   *
   * @param {HTMLElement} el 视图容器
   */
  render(el) {
    el.innerHTML = `
      <section class="toolbar">
        <div class="toolbar-left">
          <h2 class="page-title">用户管理</h2>
          <span class="page-sub">管理员可创建、编辑和删除用户</span>
        </div>
        <div class="toolbar-right">
          <button class="btn btn-primary" id="add-btn" type="button">新增用户</button>
        </div>
      </section>
      <section class="filters">
        <input id="search-input" class="input" type="search" placeholder="按昵称搜索"
               aria-label="按昵称搜索用户" value="${escapeHtml(query.searchText)}">
        <select id="role-select" class="input input-select" aria-label="按用户角色筛选">
          <option value="" ${query.userRole === '' ? 'selected' : ''}>全部角色</option>
          <option value="user" ${query.userRole === 'user' ? 'selected' : ''}>普通用户</option>
          <option value="admin" ${query.userRole === 'admin' ? 'selected' : ''}>管理员</option>
        </select>
      </section>
      <section class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户</th>
              <th>简介</th>
              <th>角色</th>
              <th>创建时间</th>
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

    $('#role-select', el).addEventListener('change', (e) => {
      query.userRole = e.target.value;
      query.current = 1;
      load(el);
    });

    $('#add-btn', el).addEventListener('click', () => openUserModal(el, null));

    load(el);
  },
};
