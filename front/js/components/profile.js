/**
 * 个人资料弹窗（当前只有「修改昵称」）
 * <p>
 * 头部用户名和「我的影像」页都要用到，所以放在组件层，两边复用同一份实现。
 */
import { userApi } from '../api.js';
import {
  $, state, toast, escapeHtml, showModal, closeModal, getFormValues,
} from '../ui.js';

/** 昵称最大长度，与后端 UserConstant.MAX_USER_NAME_LENGTH 保持一致 */
export const MAX_USER_NAME_LENGTH = 20;

/**
 * 打开「修改昵称」弹窗
 *
 * @param {object}   [options]
 * @param {Function} [options.onSuccess] 修改成功后的回调，参数为最新的登录用户信息
 */
export function openNicknameModal({ onSuccess } = {}) {
  const current = state.getCurrentUser();
  if (!current) {
    toast('请先登录', 'error');
    return;
  }

  const modal = showModal({
    title: '修改昵称',
    content: `
      <form id="nickname-form" class="form">
        <label class="field">
          <span>昵称</span>
          <input name="userName" type="text" maxlength="${MAX_USER_NAME_LENGTH}"
                 value="${escapeHtml(current.userName || '')}"
                 placeholder="最多 ${MAX_USER_NAME_LENGTH} 个字符" required>
        </label>
        <p class="form-tip">昵称会显示在页头、影像作者等位置，登录账号（${escapeHtml(current.userAccount)}）不变。</p>
        <div class="modal-foot">
          <button class="btn" type="button" data-modal-close>取消</button>
          <button class="btn btn-primary" type="submit">保存</button>
        </div>
      </form>`,
  });

  $('#nickname-form', modal).addEventListener('submit', async (e) => {
    e.preventDefault();
    const { userName } = getFormValues(e.target);
    if (!userName) {
      toast('昵称不能为空', 'error');
      return;
    }
    if (userName === current.userName) {
      // 没改动就不用打扰后端
      closeModal();
      return;
    }

    const submitBtn = $('button[type="submit"]', e.target);
    submitBtn.disabled = true;
    submitBtn.textContent = '保存中…';
    try {
      const user = await userApi.updateMy({ userName });
      // 用后端返回的最新信息覆盖全局登录态，各处的昵称才会跟着变
      state.setCurrentUser(user);
      closeModal();
      toast('昵称已更新', 'success');
      if (typeof onSuccess === 'function') {
        onSuccess(user);
      }
    } catch (err) {
      toast(err.message, 'error');
      submitBtn.disabled = false;
      submitBtn.textContent = '保存';
    }
  });
}
