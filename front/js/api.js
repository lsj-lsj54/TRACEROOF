/**
 * 后端接口封装
 * <p>
 * 后端 context-path 为 /api，跨域配置已开启 allowCredentials，
 * 因此所有请求都要带上 credentials: 'include'（携带 satoken Cookie 维持登录态）。
 * 该 Cookie 已开启 HttpOnly，JS 读取不到，也不需要读——登录态由浏览器自动带上。
 */

/**
 * 后端基础地址
 * <p>
 * 部署时可以只改这一处；也支持在 index.html 里于本模块之前注入
 * <code>window.__API_BASE__ = '/api'</code> 来覆盖（同源部署推荐这么写）。
 */
export const API_BASE = (typeof window !== 'undefined' && window.__API_BASE__)
  || 'http://localhost:8666/api';

/**
 * 首页列表是否使用带缓存的接口
 * <p>
 * 打开后走 /evidence/list/page/vo/cache（Caffeine + Redis 多级缓存），
 * 前提是服务端 Redis 可用；否则接口会连接失败，首页加载不出来。
 * 本地没起 Redis 时保持 false 即可。
 */
export const USE_CACHE = (typeof window !== 'undefined' && window.__USE_CACHE__ === true);

/** 业务状态码，与后端 ErrorCode 保持一致 */
export const CODE = {
  SUCCESS: 0,
  PARAMS_ERROR: 40000,
  NOT_LOGIN_ERROR: 40100,
  NO_AUTH_ERROR: 40101,
  NOT_FOUND_ERROR: 40400,
  FORBIDDEN_ERROR: 40300,
  SYSTEM_ERROR: 50000,
};

/** 登录态失效时统一跳转，由 app.js 注入，避免 api 层依赖 UI */
let onNotLogin = null;

/**
 * 注册未登录回调
 *
 * @param {Function} handler 回调函数
 */
export function setNotLoginHandler(handler) {
  onNotLogin = handler;
}

/**
 * 统一请求方法，自动解包 BaseResponse
 *
 * @param {string} url    接口路径，以 / 开头
 * @param {object} options fetch 配置，body 为普通对象时自动转 JSON
 * @param {AbortSignal} [options.signal] 调用方取消信号
 * @param {number} [options.timeout] 超时毫秒数，默认 30000，传 0 可关闭
 * @returns {Promise<any>} 接口返回的 data 字段
 */
async function request(url, options = {}) {
  const {
    method = 'GET', body, headers = {}, signal, timeout = 30000,
  } = options;
  const controller = new AbortController();
  const init = {
    method,
    // 跨域携带 Cookie，否则登录态会丢
    credentials: 'include',
    headers: { ...headers },
    signal: controller.signal,
  };

  if (body instanceof FormData) {
    // FormData 不能手动设置 Content-Type，需要浏览器自己补 boundary
    init.body = body;
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  let timedOut = false;
  const onExternalAbort = () => controller.abort(signal.reason);
  if (signal) {
    if (signal.aborted) {
      onExternalAbort();
    } else {
      signal.addEventListener('abort', onExternalAbort, { once: true });
    }
  }
  const timeoutMs = Number(timeout);
  const timer = Number.isFinite(timeoutMs) && timeoutMs > 0
    ? setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeoutMs)
    : null;

  let response;
  try {
    response = await fetch(API_BASE + url, init);
  } catch (e) {
    // 调用方主动取消时保留原始 AbortError，便于调用方按取消流程处理。
    if (signal && signal.aborted) {
      throw e;
    }
    if (timedOut) {
      const error = new Error(`请求超时（${timeoutMs}ms），请稍后重试`);
      error.name = 'TimeoutError';
      throw error;
    }
    // 服务端在解析请求体阶段直接断开连接时，浏览器同样只会抛网络错误，
    // 因此这里要提示排查方向，不能只让用户以为后端没启动。
    throw new Error(`请求发送失败（${API_BASE}）。请确认后端已启动；若正在上传大文件，可能是服务端体积限制导致连接被断开。`);
  } finally {
    if (timer !== null) {
      clearTimeout(timer);
    }
    if (signal) {
      signal.removeEventListener('abort', onExternalAbort);
    }
  }

  if (!response.ok) {
    const text = await response.text();
    let message = text;
    try {
      const result = JSON.parse(text);
      message = (result && result.message) || text;
    } catch (e) {
      // 非 JSON 响应直接把文本作为服务端错误信息。
    }
    throw new Error(message || `请求失败（HTTP ${response.status}）`);
  }

  const text = await response.text();
  let result;
  try {
    result = JSON.parse(text);
  } catch (e) {
    // 返回的不是 JSON，通常意味着请求没有走到后端（被网关/代理拦截或重定向）
    throw new Error(`服务端返回异常内容（HTTP ${response.status}）：${text.slice(0, 60)}`);
  }
  if (result.code !== CODE.SUCCESS) {
    if (result.code === CODE.NOT_LOGIN_ERROR && typeof onNotLogin === 'function') {
      onNotLogin();
    }
    throw new Error(result.message || '请求失败');
  }
  return result.data;
}

/** 把对象里为空的值去掉，避免给后端传一堆 null */
function cleanParams(params = {}) {
  const result = {};
  Object.keys(params).forEach((key) => {
    const value = params[key];
    if (value === null || value === undefined || value === '') {
      return;
    }
    if (Array.isArray(value) && value.length === 0) {
      return;
    }
    result[key] = value;
  });
  return result;
}

/* ------------------------------ 用户模块 ------------------------------ */

export const userApi = {
  /** 用户注册，返回新用户 id */
  register: (data) => request('/user/register', { method: 'POST', body: data }),
  /** 用户登录，返回脱敏后的登录用户信息 */
  login: (data) => request('/user/login', { method: 'POST', body: data }),
  /** 获取当前登录用户，未登录会抛 40100 */
  getLoginUser: () => request('/user/get/login'),
  /** 用户注销 */
  logout: () => request('/user/logout', { method: 'POST', body: {} }),
  /** 修改当前登录用户的昵称，返回最新的登录用户信息 */
  updateMy: (data) => request('/user/update/my', { method: 'POST', body: data }),
  /** 创建用户（仅管理员） */
  add: (data) => request('/user/add', { method: 'POST', body: data }),
  /** 根据 id 获取用户（仅管理员） */
  getById: (id) => request(`/user/get?id=${encodeURIComponent(id)}`),
  /** 根据 id 获取脱敏用户信息 */
  getVOById: (id) => request(`/user/get/vo?id=${encodeURIComponent(id)}`),
  /** 更新用户（仅管理员） */
  update: (data) => request('/user/update', { method: 'POST', body: data }),
  /** 删除用户（仅管理员） */
  delete: (id) => request('/user/delete', { method: 'POST', body: { id } }),
  /** 分页获取用户脱敏列表（仅管理员） */
  listVOByPage: (query) => request('/user/list/page/vo', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /** 按昵称/账号搜索用户（脱敏，供邀请成员时匹配候选） */
  search: (keyword) => request(`/user/search?keyword=${encodeURIComponent(keyword)}`),
};

/* ------------------------------ 影像模块 ------------------------------ */

export const evidenceApi = {
  /**
   * 上传影像（同一个接口也用于重新上传）
   *
   * @param {File}   file    影像文件
   * @param {string} [id]    影像 id，传了表示覆盖已有影像
   * @param {string} [archiveId] 档案库 id，缺省传 0 表示上传到公共案例库
   */
  upload: (file, id, archiveId) => {
    const formData = new FormData();
    formData.append('file', file);
    if (id !== undefined && id !== null && id !== '') {
      // 后端 EvidenceUploadRequest.id 是 Long，表单字段用字符串即可绑定
      formData.append('id', String(id));
    }
    // 统一携带 archiveId：公共案例库传 0，私有/团队档案库传对应档案库 id
    formData.append('archiveId', String(archiveId === undefined || archiveId === null || archiveId === '' ? 0 : archiveId));
    return request('/evidence/upload', { method: 'POST', body: formData });
  },
  /**
   * 通过 URL 上传影像（后端会把远程影像下载后转存到对象存储）
   *
   * @param {string}      evidenceUrl 影像地址
   * @param {string}      [id]       影像 id，传了表示覆盖已有影像
   * @param {string}      [archiveId]  档案库 id，缺省传 0 表示上传到公共案例库
   */
  uploadByUrl: (evidenceUrl, id, archiveId) => {
    const body = { evidenceUrl };
    if (id !== undefined && id !== null && id !== '') {
      body.id = String(id);
    }
    // 统一携带 archiveId：公共案例库传 0，私有/团队档案库传对应档案库 id
    body.archiveId = String(archiveId === undefined || archiveId === null || archiveId === '' ? 0 : archiveId);
    return request('/evidence/upload/url', { method: 'POST', body });
  },
  /** 删除影像（本人或管理员） */
  delete: (id) => request('/evidence/delete', { method: 'POST', body: { id } }),
  /** 更新影像（仅管理员） */
  update: (data) => request('/evidence/update', { method: 'POST', body: data }),
  /** 编辑影像（本人或管理员） */
  edit: (data) => request('/evidence/edit', { method: 'POST', body: data }),
  /** 根据 id 获取影像实体（仅管理员） */
  getById: (id) => request(`/evidence/get?id=${encodeURIComponent(id)}`),
  /** 根据 id 获取影像封装类 */
  getVOById: (id) => request(`/evidence/get/vo?id=${encodeURIComponent(id)}`),
  /** 分页获取影像实体列表（仅管理员） */
  listByPage: (query) => request('/evidence/list/page', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /** 分页获取影像封装列表，后端限制 size <= 20 */
  listVOByPage: (query) => request('/evidence/list/page/vo', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /**
   * 分页获取影像封装列表（带 Caffeine + Redis 多级缓存）
   * <p>
   * 后端需要 Redis 可用，否则该接口会连接失败。
   * 由 config.js 里的 __USE_CACHE__ 控制前端是否走这条路径。
   */
  listVOByPageWithCache: (query) => request('/evidence/list/page/vo/cache', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /** 影像审核（仅管理员） */
  review: (data) => request('/evidence/review', { method: 'POST', body: data }),
};

/* ------------------------------ 档案库模块 ------------------------------ */

/**
 * 档案库接口
 * <p>
 * 规则与后端一致：私有档案库只有创建者（或管理员）能查看和修改；
 * 团队档案库（archiveType = 1）的成员按角色获得查看/操作权限；
 * `archiveId = 0` 表示公共案例库，`> 0` 表示对应的私有/团队档案库。
 */
export const archiveApi = {
  /**
   * 创建档案库，返回新档案库 id
   * <p>
   * data.archiveType：0-私有（默认） 1-团队；普通用户只能创建普通版，
   * 且每种类型的档案库只能创建一个。
   */
  add: (data) => request('/archive/add', { method: 'POST', body: data }),
  /** 编辑档案库名称（创建者或管理员） */
  edit: (data) => request('/archive/edit', { method: 'POST', body: data }),
  /** 更新档案库（仅管理员，可改级别与限额） */
  update: (data) => request('/archive/update', { method: 'POST', body: data }),
  /** 删除档案库（创建者或管理员），档案库内的影像会一并删除 */
  delete: (id) => request('/archive/delete', { method: 'POST', body: { id } }),
  /** 根据 id 获取档案库实体（仅管理员） */
  getById: (id) => request(`/archive/get?id=${encodeURIComponent(id)}`),
  /** 根据 id 获取档案库封装类（创建者或管理员） */
  getVOById: (id) => request(`/archive/get/vo?id=${encodeURIComponent(id)}`),
  /** 分页获取档案库实体列表（仅管理员） */
  listByPage: (query) => request('/archive/list/page', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /** 分页获取档案库封装列表（仅管理员） */
  listVOByPage: (query) => request('/archive/list/page/vo', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /** 分页获取当前用户自己的档案库列表（用户 id 由后端从登录态写入） */
  myListVOByPage: (query) => request('/archive/my/list/page/vo', {
    method: 'POST',
    body: cleanParams(query),
  }),
  /** 档案库级别字典：text / value / maxSize / maxCount */
  listLevel: () => request('/archive/list/level'),
};

/**
 * 团队成员接口
 * <p>
 * 仅团队档案库（archiveType = 1）有成员概念。邀请/移除/改角色的权限由后端校验：
 * 创建者与团队管理员可邀请和移除成员，仅创建者可调整角色，成员可移除自己（退出团队）。
 */
export const archiveUserApi = {
  /** 邀请成员：{ archiveId, userId, archiveRole }，archiveRole 缺省为 viewer */
  add: (data) => request('/archive/user/add', { method: 'POST', body: data }),
  /** 移除成员（传成员记录 id），也可用于退出团队 */
  delete: (id) => request('/archive/user/delete', { method: 'POST', body: { id } }),
  /** 调整成员角色：{ id, archiveRole } */
  edit: (data) => request('/archive/user/edit', { method: 'POST', body: data }),
  /** 查询档案库成员列表：{ archiveId, archiveRole? } */
  list: (query) => request('/archive/user/list', { method: 'POST', body: cleanParams(query) }),
  /** 查询当前用户加入的团队档案库列表 */
  listMy: () => request('/archive/user/list/my', { method: 'POST', body: {} }),
};

/**
 * 档案库分析接口
 * <p>
 * 统一传 { archiveId, timeDimension, limit }：archiveId 为空表示全档案库汇总（仅管理员），
 * timeDimension 仅 trend 接口使用（day/week/month），limit 控制词云/排行条数。
 */
export const archiveAnalyzeApi = {
  /** 资源使用分析（仪表盘：容量/数量占比） */
  usage: (data) => request('/archive/analyze/usage', { method: 'POST', body: cleanParams(data) }),
  /** 分类分析（分组条形图：数量 + 总大小） */
  category: (data) => request('/archive/analyze/category', { method: 'POST', body: cleanParams(data) }),
  /** 标签分析（词云图） */
  tag: (data) => request('/archive/analyze/tag', { method: 'POST', body: cleanParams(data) }),
  /** 大小分段分析（饼图） */
  size: (data) => request('/archive/analyze/size', { method: 'POST', body: cleanParams(data) }),
  /** 上传趋势（折线图，data.timeDimension = day/week/month） */
  trend: (data) => request('/archive/analyze/trend', { method: 'POST', body: cleanParams(data) }),
  /** 档案库使用排行（柱状图，仅管理员） */
  rank: (data) => request('/archive/analyze/rank', { method: 'POST', body: cleanParams(data) }),
};

/* ------------------------------ 文件模块 ------------------------------ */

export const fileApi = {
  /** 测试文件上传（仅管理员） */
  testUpload: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return request('/file/test/upload', { method: 'POST', body: formData });
  },
  /** 测试文件下载地址 */
  testDownloadUrl: (filepath) => `${API_BASE}/file/test/download/?filepath=${encodeURIComponent(filepath)}`,
};
