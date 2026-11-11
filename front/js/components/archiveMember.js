/**
 * 档案库成员管理弹窗
 * <p>
 * 团队档案库的成员邀请、角色调整、移除/退出统一在这处理；
 * 私有档案库不会进入这里。权限由后端最终把关，前端按当前登录身份控制按钮显隐，
 * 避免误操作（被后端拒绝时也会 toast 具体原因）。
 */
import { archiveUserApi, userApi } from '../api.js';
import {
  $, $$, state, toast, escapeHtml, formatTime,
  showModal, closeModal, getFormValues, archiveRoleInfo, archiveRoleOptions, debounce,
} from '../ui.js';

/**
 * 判断当前用户是否有成员管理权限（邀请、移除他人）
 *
 * @param {object} archive      档案库信息
 * @param {Array}  memberList 当前成员列表
 * @returns {boolean} 是否可管理
 */
function canManage(archive, memberList) {
  if (!state.isLogin()) {
    return false;
  }
  if (state.isAdmin()) {
    return true;
  }
  if (String(archive.userId) === String(state.currentUser.id)) {
    return true;
  }
  const me = memberList.find(
    (m) => String(m.userId) === String(state.currentUser.id),
  );
  return me && me.archiveRole === 'admin';
}

/**
 * 判断当前用户是否能调整角色（仅创建者 / 系统管理员）
 */
function canEditRole(archive) {
  return state.isLogin() && (state.isAdmin() || String(archive.userId) === String(state.currentUser.id));
}

/** 打开成员管理弹窗 */
export async function openArchiveMemberModal(archive, options = {}) {
  if (!archive || !archive.id) {
    toast('缺少档案库信息', 'error');
    return;
  }
  const isTeam = Number(archive.archiveType) === 1;
  if (!isTeam) {
    toast('私有档案库没有成员概念', 'error');
    return;
  }

  const modal = showModal({
    title: `「${escapeHtml(archive.archiveName)}」成员管理`,
    content: `
      <div id="member-loading" class="loading">加载成员中…</div>
      <div id="member-body" class="member-body hidden"></div>`,
  });

  const loading = $('#member-loading', modal);
  const body = $('#member-body', modal);

  let memberList = [];
  try {
    memberList = await archiveUserApi.list({ archiveId: archive.id });
    loading.classList.add('hidden');
    body.classList.remove('hidden');
  } catch (e) {
    loading.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`;
    return;
  }

  const myRecord = state.isLogin()
    ? memberList.find((m) => String(m.userId) === String(state.currentUser.id))
    : null;
  const manageable = canManage(archive, memberList);

  /** 重新渲染弹窗内容 */
  function render() {
    const canInvite = manageable;
    const inviteHtml = canInvite
      ? `
        <form id="member-invite-form" class="form member-invite">
          <div class="form-row">
            <label class="field invite-user-field">
              <span>邀请用户（按昵称/账号搜索）</span>
              <input name="keyword" type="text" autocomplete="off" required placeholder="输入昵称或账号">
              <input name="userId" type="hidden">
              <div class="invite-candidates hidden"></div>
            </label>
            <label class="field">
              <span>角色</span>
              <select name="archiveRole">${archiveRoleOptions('viewer')}</select>
            </label>
            <button class="btn btn-primary" type="submit">邀请</button>
          </div>
        </form>`
      : '';

    if (memberList.length === 0) {
      body.innerHTML = `${inviteHtml}<div class="empty">暂无成员</div>`;
      bindInvite();
      return;
    }

    const rows = memberList.map((member) => {
      const role = archiveRoleInfo(member.archiveRole);
      const isCreator = String(archive.userId) === String(member.userId);
      const isMe = state.isLogin() && String(member.userId) === String(state.currentUser.id);
      // 操作按钮
      let actions = '';
      if (canEditRole(archive) && !isCreator) {
        actions += `
          <select class="btn-sm member-role-select" data-id="${member.id}">
            ${archiveRoleOptions(member.archiveRole)}
          </select>`;
      }
      if (!isCreator) {
        if (manageable || isMe) {
          actions += `
            <button class="btn btn-sm btn-danger member-remove-btn" data-id="${member.id}" type="button">
              ${isMe ? '退出团队' : '移除'}
            </button>`;
        }
      } else {
        actions += `<span class="tag tag-admin-role">创建者</span>`;
      }
      const userName = member.user ? escapeHtml(member.user.userName || member.user.userAccount) : `用户 ${member.userId}`;
      return `
        <div class="member-row">
          <div class="member-info">
            <span class="member-name">${userName}${isMe ? '（我）' : ''}</span>
            <span class="tag ${role.cls}">${role.text}</span>
          </div>
          <div class="member-actions">${actions}</div>
        </div>`;
    }).join('');

    body.innerHTML = `${inviteHtml}
      <div class="member-list">${rows}</div>`;

    bindInvite();
    bindRoleChange();
    bindRemove(archive, memberList);
  }

  /** 绑定邀请表单 */
  function bindInvite() {
    const form = $('#member-invite-form', modal);
    if (!form) return;

    const keywordInput = form.querySelector('input[name="keyword"]');
    const userIdInput = form.querySelector('input[name="userId"]');
    const candidateBox = form.querySelector('.invite-candidates');

    /** 已邀请/已在团队的用户 id 集合，用于在候选里做去重提示 */
    const existingIds = new Set(memberList.map((m) => String(m.userId)));

    /** 清空候选与已选用户 */
    function resetSelection() {
      userIdInput.value = '';
      if (candidateBox) {
        candidateBox.innerHTML = '';
        candidateBox.classList.add('hidden');
      }
    }

    /** 渲染候选列表 */
    function renderCandidates(users) {
      if (!candidateBox) return;
      if (!users || users.length === 0) {
        candidateBox.innerHTML = '<div class="invite-candidate-empty">未找到匹配的用户</div>';
        candidateBox.classList.remove('hidden');
        return;
      }
      candidateBox.innerHTML = users.map((u) => {
        const already = existingIds.has(String(u.id));
        const name = escapeHtml(u.userName || u.userAccount);
        const account = u.userAccount ? escapeHtml(u.userAccount) : '';
        const sub = account && account !== name ? `<span class="candidate-account">@${account}</span>` : '';
        const tag = already ? '<span class="tag tag-viewer">已在团队</span>' : '';
        return `
          <button class="invite-candidate" type="button" data-id="${u.id}" data-name="${name}" ${already ? 'disabled' : ''}>
            <span class="candidate-name">${name}</span>${sub}${tag}
          </button>`;
      }).join('');
      candidateBox.classList.remove('hidden');
    }

    // 输入关键字后防抖搜索
    keywordInput.addEventListener('input', debounce(async () => {
      const kw = keywordInput.value.trim();
      resetSelection();
      if (!kw) {
        candidateBox.innerHTML = '';
        candidateBox.classList.add('hidden');
        return;
      }
      try {
        const users = await userApi.search(kw);
        renderCandidates(users || []);
      } catch (err) {
        toast(err.message, 'error');
      }
    }, 300));

    // 点击候选填充 userId 与昵称
    if (candidateBox) {
      candidateBox.addEventListener('click', (e) => {
        const btn = e.target.closest('.invite-candidate');
        if (!btn || btn.disabled) return;
        userIdInput.value = btn.dataset.id;
        keywordInput.value = btn.dataset.name;
        candidateBox.innerHTML = '';
        candidateBox.classList.add('hidden');
      });
    }

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const values = getFormValues(e.target);
      // 用户 id 是雪花长整型，必须按字符串传递，绝不能 Number() 转数字，
      // 否则会超出 JS 安全整数范围导致精度丢失（差一位就查不到用户）
      const userId = values.userId;
      if (!userId) {
        toast('请先从搜索结果中选择一位用户', 'error');
        return;
      }
      try {
        await archiveUserApi.add({
          archiveId: archive.id,
          userId,
          archiveRole: values.archiveRole || 'viewer',
        });
        toast('邀请成功', 'success');
        e.target.reset();
        resetSelection();
        memberList = await archiveUserApi.list({ archiveId: archive.id });
        render();
        if (typeof options.onChanged === 'function') {
          options.onChanged();
        }
      } catch (err) {
        toast(err.message, 'error');
      }
    });
  }

  /** 绑定角色变更 */
  function bindRoleChange() {
    $$('.member-role-select', modal).forEach((select) => {
      select.addEventListener('change', async () => {
        // 成员记录 id 是雪花长整型，必须按字符串传递，避免精度丢失
        const id = select.dataset.id;
        const archiveRole = select.value;
        try {
          await archiveUserApi.edit({ id, archiveRole });
          toast('角色调整成功', 'success');
          memberList = await archiveUserApi.list({ archiveId: archive.id });
          render();
          if (typeof options.onChanged === 'function') {
            options.onChanged();
          }
        } catch (err) {
          toast(err.message, 'error');
          render();
        }
      });
    });
  }

  /** 绑定移除 / 退出按钮 */
  function bindRemove(currentArchive, currentList) {
    $$('.member-remove-btn', modal).forEach((btn) => {
      btn.addEventListener('click', async () => {
        // 成员记录 id 是雪花长整型，必须按字符串传递，避免精度丢失
        const id = btn.dataset.id;
        const member = currentList.find((m) => String(m.id) === String(id));
        const isMe = state.isLogin() && member && String(member.userId) === String(state.currentUser.id);
        try {
          await archiveUserApi.delete(id);
          toast(isMe ? '已退出团队' : '移除成功', 'success');
          if (isMe) {
            closeModal();
          } else {
            memberList = await archiveUserApi.list({ archiveId: currentArchive.id });
            render();
          }
          if (typeof options.onChanged === 'function') {
            options.onChanged();
          }
        } catch (err) {
          toast(err.message, 'error');
        }
      });
    });
  }

  render();
}
