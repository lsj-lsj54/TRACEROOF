/**
 * 运行时配置（部署时改这里，不用重新构建前端）
 *
 * 本文件必须在 js/app.js 之前加载，这样 js/api.js 才能读到 window.__API_BASE__。
 * 留空表示使用 js/api.js 里的默认值（本地开发用 http://localhost:8666/api）。
 *
 * 部署到服务器时推荐改成相对路径 '/api'：
 * 前端由 Nginx 托管、/api 反代到后端，浏览器眼里只有一个源，不存在跨域问题。
 *
 * 如果使用提供的 Nginx 配置，可以用 __API_BASE__ 占位符，
 * 由 Nginx 的 sub_filter 在响应时替换成真实地址。
 */
window.__API_BASE__ = '';

/**
 * 首页列表是否使用带多级缓存的接口（Caffeine + Redis）
 *
 * 打开后首页会请求 /evidence/list/page/vo/cache。
 * 前提：服务端 Redis 可用，否则该接口会连接失败、首页加载不出来。
 * 本地开发没起 Redis 时保持 false，或先跑一个：
 *   docker run -d --name redis -p 6379:6379 redis:7-alpine
 *
 * 生产环境用 Nginx 部署时，这里会被自动置为 true（见 deploy/nginx/default.conf）。
 */
window.__USE_CACHE__ = false;
