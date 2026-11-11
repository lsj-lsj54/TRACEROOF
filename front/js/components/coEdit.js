/**
 * 影像在线协同编辑弹窗
 * <p>
 * 打开后建立 WebSocket 连接，尝试进入编辑状态；只有拿到编辑权的用户才能操作，
 * 缩放 / 旋转动作会实时广播给同一张影像的其他在线用户，实现「多人同步编辑」。
 * <p>
 * 权限由后端握手阶段判定（登录 + 影像编辑权限），被拒绝时这里会给出明确提示。
 * 注意：这里的缩放 / 旋转只是实时同步演示，不会写入影像文件。
 */
import { $, $$, state, toast, showModal, escapeHtml } from '../ui.js';
import { EvidenceEditSocketClient, EDIT_ACTION } from './evidenceEditSocket.js';

/** 缩放步进与范围 */
const ZOOM_STEP = 1.1;
const MIN_SCALE = 0.2;
const MAX_SCALE = 5;

/** 每次旋转的角度 */
const ROTATE_STEP = 90;

/**
 * 打开协同编辑弹窗
 *
 * @param {object} evidence 影像对象（需包含 id、url）
 * @returns {HTMLElement} 弹窗元素
 */
export function openCoEdit(evidence) {
  if (!evidence || !evidence.id) {
    toast('影像信息缺失', 'error');
    return null;
  }
  const myId = state.isLogin() ? String(state.getCurrentUser().id) : '';
  const url = evidence.url || evidence.thumbnailUrl || '';

  // 编辑状态（只在本地记录，用于渲染与按钮权限）
  const view = {
    scale: 1,
    rotation: 0,
    isEditor: false,
    opened: false, // 连接是否成功建立，用于区分「握手被拒」
    closed: false,
  };

  const modal = showModal({
    title: '在线协同编辑',
    width: '720px',
    content: `
      <div class="coedit">
        <div class="coedit-stage">
          <img class="coedit-img" id="coedit-img" src="${escapeHtml(url)}"
               alt="${escapeHtml(evidence.name || '')}" draggable="false">
        </div>
        <div class="coedit-toolbar">
          <button class="btn btn-sm" type="button" data-coaction="${EDIT_ACTION.ZOOM_OUT}">− 缩小</button>
          <button class="btn btn-sm" type="button" data-coaction="${EDIT_ACTION.ZOOM_IN}">＋ 放大</button>
          <button class="btn btn-sm" type="button" data-coaction="${EDIT_ACTION.ROTATE_LEFT}">⟲ 左旋</button>
          <button class="btn btn-sm" type="button" data-coaction="${EDIT_ACTION.ROTATE_RIGHT}">⟳ 右旋</button>
        </div>
        <p class="coedit-status" id="coedit-status" aria-live="polite">正在连接…</p>
        <p class="coedit-hint">缩放 / 旋转为实时同步演示，不会写入影像文件。</p>
      </div>
      <div class="modal-foot">
        <button class="btn" type="button" data-modal-close>关闭</button>
      </div>`,
  });

  const img = $('#coedit-img', modal);
  const statusEl = $('#coedit-status', modal);
  const actionBtns = $$('[data-coaction]', modal);

  /** 更新状态栏文案与样式 */
  function setStatus(text, isError = false) {
    statusEl.textContent = text || '';
    statusEl.classList.toggle('is-error', !!isError);
  }

  /** 把当前缩放 / 旋转应用到影像上 */
  function renderTransform() {
    img.style.transform = `scale(${view.scale}) rotate(${view.rotation}deg)`;
  }

  /** 应用一个编辑动作 */
  function applyAction(action) {
    switch (action) {
      case EDIT_ACTION.ZOOM_IN:
        view.scale = Math.min(MAX_SCALE, view.scale * ZOOM_STEP);
        break;
      case EDIT_ACTION.ZOOM_OUT:
        view.scale = Math.max(MIN_SCALE, view.scale / ZOOM_STEP);
        break;
      case EDIT_ACTION.ROTATE_LEFT:
        view.rotation -= ROTATE_STEP;
        break;
      case EDIT_ACTION.ROTATE_RIGHT:
        view.rotation += ROTATE_STEP;
        break;
      default:
        return;
    }
    renderTransform();
  }

  /** 根据是否拿到编辑权，切换操作按钮可用态 */
  function renderControls() {
    actionBtns.forEach((btn) => {
      btn.disabled = !view.isEditor;
    });
  }

  /** 处理服务端推送的消息 */
  function handleMessage(msg) {
    if (!msg || !msg.type) {
      return;
    }
    switch (msg.type) {
      case 'INFO':
        setStatus(msg.message || '');
        break;
      case 'ERROR':
        toast(msg.message || '操作失败', 'error');
        setStatus(msg.message || '', true);
        view.isEditor = false;
        renderControls();
        break;
      case 'ENTER_EDIT':
        setStatus(msg.message || '');
        // 是我自己进入编辑，才有操作权限
        view.isEditor = !!(msg.user && String(msg.user.id) === myId);
        renderControls();
        break;
      case 'EXIT_EDIT':
        setStatus(msg.message || '');
        if (msg.user && String(msg.user.id) === myId) {
          view.isEditor = false;
          renderControls();
        }
        break;
      case 'EDIT_ACTION':
        // 服务端广播时已排除发送者，自己不会收到自己的动作，不会重复应用
        if (msg.editAction) {
          applyAction(msg.editAction);
        }
        break;
      default:
        break;
    }
  }

  const client = new EvidenceEditSocketClient(evidence.id, {
    onOpen: () => {
      view.opened = true;
      setStatus('连接成功，正在进入编辑…');
      client.enterEdit();
    },
    onMessage: handleMessage,
    onClose: () => {
      if (!view.opened) {
        setStatus('连接被拒绝：未登录或没有该影像的编辑权限', true);
      } else {
        setStatus('连接已断开', true);
      }
      view.isEditor = false;
      renderControls();
    },
    onError: () => {
      if (!view.opened) {
        setStatus('连接失败，请确认后端已启动', true);
      }
    },
  });

  // 操作按钮：本地立即生效 + 广播给其他用户
  actionBtns.forEach((btn) => {
    btn.addEventListener('click', () => {
      if (!view.isEditor) {
        toast('当前没有编辑权限，请等待他人结束编辑', 'info');
        return;
      }
      const action = btn.dataset.coaction;
      applyAction(action);
      client.sendAction(action);
    });
  });

  renderControls();
  client.connect();

  // 弹窗被关闭时退出编辑并断开连接（覆盖遮罩点击 / 关闭按钮 / Esc 三条路径）
  const cleanup = () => {
    if (view.closed) {
      return;
    }
    view.closed = true;
    if (view.isEditor) {
      client.exitEdit();
    }
    client.close();
    observer.disconnect();
  };
  const observer = new MutationObserver(() => {
    if (!modal.isConnected) {
      cleanup();
    }
  });
  observer.observe(document.getElementById('modal-root'), { childList: true });

  return modal;
}
