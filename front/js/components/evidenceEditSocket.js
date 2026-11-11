/**
 * 影像在线协同编辑 —— WebSocket 客户端封装（原生 WebSocket，无第三方依赖）
 *
 * 与后端 EvidenceEditHandler / WsHandshakeInterceptor 配套：
 *   - 连接地址：ws(s)://<host>:8666/api/ws/evidence/edit?evidenceId=xxx
 *   - 握手阶段后端会校验：登录态（satoken Cookie）+ 影像编辑权限，不通过会被拒绝（onclose）。
 *   - 消息协议（JSON 字符串）：
 *       发送：{ type: "ENTER_EDIT" | "EXIT_EDIT" | "EDIT_ACTION", editAction?: "ZOOM_IN" | ... }
 *       接收：{ type: "INFO" | "ERROR" | "ENTER_EDIT" | "EXIT_EDIT" | "EDIT_ACTION", message, editAction, user }
 *
 * 登录态说明：satoken 是 HttpOnly Cookie，JS 读不到，也不需要读——
 * 同站点（localhost 不同端口也算同站，Cookie 不带端口）时浏览器会自动带上，
 * 所以直接 `new WebSocket('ws://localhost:8666/...')` 就能带上 Cookie。
 *
 * 用法示例：
 *   import { EvidenceEditSocketClient } from './components/evidenceEditSocket.js';
 *   const client = new EvidenceEditSocketClient(evidenceId, {
 *     onMessage: (msg) => {
 *       if (msg.type === 'EDIT_ACTION') applyEditAction(msg.editAction);
 *       if (msg.type === 'ERROR') uiToast(msg.message);
 *     },
 *     onClose: () => console.log('连接已关闭'),
 *   });
 *   client.connect();   // 建立连接（握手成功后一般立即 client.enterEdit()）
 */

/**
 * 从 api.js 的 API_BASE 推导 WebSocket 基地址。
 * 默认后端 http://localhost:8666/api，对应 ws://localhost:8666/api。
 * 部署时可改 config.js 的 window.__API_BASE__，这里会自动跟随。
 */
function buildWsBase() {
  const apiBase = (typeof window !== 'undefined' && window.__API_BASE__)
    || 'http://localhost:8666/api';
  // http -> ws，https -> wss
  const wsBase = apiBase.replace(/^http/i, 'ws');
  return wsBase.replace(/\/$/, '');
}

/** 编辑动作常量，与后端 EvidenceEditActionEnum 保持一致 */
export const EDIT_ACTION = {
  ZOOM_IN: 'ZOOM_IN',
  ZOOM_OUT: 'ZOOM_OUT',
  ROTATE_LEFT: 'ROTATE_LEFT',
  ROTATE_RIGHT: 'ROTATE_RIGHT',
};

/** 消息类型常量，与后端 EvidenceEditMessageTypeEnum 保持一致 */
export const EDIT_MESSAGE_TYPE = {
  ENTER_EDIT: 'ENTER_EDIT',
  EXIT_EDIT: 'EXIT_EDIT',
  EDIT_ACTION: 'EDIT_ACTION',
};

export class EvidenceEditSocketClient {
  /**
   * @param {string|number} evidenceId 影像 id
   * @param {object}        [handlers] 回调
   * @param {Function}      [handlers.onOpen]    连接建立后触发
   * @param {Function}      [handlers.onMessage] 收到服务端消息，入参为解析后的对象
   * @param {Function}      [handlers.onClose]   连接关闭（含握手被拒绝）
   * @param {Function}      [handlers.onError]   连接出错
   */
  constructor(evidenceId, handlers = {}) {
    this.evidenceId = evidenceId;
    this.handlers = handlers;
    this.socket = null;
  }

  /** 建立连接 */
  connect() {
    if (this.socket) {
      return;
    }
    const url = `${buildWsBase()}/ws/evidence/edit?evidenceId=${encodeURIComponent(this.evidenceId)}`;
    this.socket = new WebSocket(url);

    this.socket.onopen = () => {
      if (typeof this.handlers.onOpen === 'function') {
        this.handlers.onOpen();
      }
    };

    this.socket.onmessage = (event) => {
      if (typeof this.handlers.onMessage !== 'function') {
        return;
      }
      let message;
      try {
        message = JSON.parse(event.data);
      } catch (e) {
        console.error('[evidenceEditSocket] 无法解析服务端消息：', event.data);
        return;
      }
      this.handlers.onMessage(message);
    };

    this.socket.onclose = () => {
      if (typeof this.handlers.onClose === 'function') {
        this.handlers.onClose();
      }
    };

    this.socket.onerror = () => {
      if (typeof this.handlers.onError === 'function') {
        this.handlers.onError();
      }
    };
  }

  /** 进入编辑状态（只有当前没有其他人编辑时才能成功成为编辑者） */
  enterEdit() {
    this._send({ type: EDIT_MESSAGE_TYPE.ENTER_EDIT });
  }

  /** 退出编辑状态，释放编辑锁 */
  exitEdit() {
    this._send({ type: EDIT_MESSAGE_TYPE.EXIT_EDIT });
  }

  /**
   * 执行编辑动作并广播给其他用户
   * @param {string} action EDIT_ACTION 里的一个值
   */
  sendAction(action) {
    this._send({ type: EDIT_MESSAGE_TYPE.EDIT_ACTION, editAction: action });
  }

  /** 关闭连接（会自动触发 onclose） */
  close() {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
  }

  _send(data) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(data));
    } else {
      console.warn('[evidenceEditSocket] 连接未就绪，忽略发送：', data);
    }
  }
}
