/**
 * 前端静态服务器（零依赖，直接依赖 Node 内置模块）
 *
 * 作用：
 *  1. 把 front 目录当作静态站点托管；
 *  2. 把 /api/** 反向代理到后端（默认 http://localhost:8666），
 *     这样浏览器只访问同源地址，跨域和 Cookie 登录态问题都不存在。
 *
 * 启动： node server.js
 * 可选环境变量：
 *  - PORT      前端端口，默认 8667
 *  - API_PORT  后端端口，默认 8666
 *  - API_HOST  后端地址，默认 localhost
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const PORT = Number(process.env.PORT || 8667);
const API_PORT = Number(process.env.API_PORT || 8666);
const API_HOST = process.env.API_HOST || 'localhost';

/** 常见静态资源类型 */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.map': 'application/json; charset=utf-8',
};

/**
 * 把请求代理到后端
 *
 * @param {http.IncomingMessage} req 客户端请求
 * @param {http.ServerResponse}  res 客户端响应
 */
function proxy(req, res) {
  const options = {
    host: API_HOST,
    port: API_PORT,
    method: req.method,
    // 原样透传路径（后端 context-path 就是 /api）
    path: req.url,
    headers: { ...req.headers, host: `${API_HOST}:${API_PORT}` },
  };

  const proxyReq = http.request(options, (proxyRes) => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', () => {
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      code: 50000,
      data: null,
      message: `后端未启动，请先运行 Spring Boot（${API_HOST}:${API_PORT}）`,
    }));
  });

  req.pipe(proxyReq);
}

/**
 * 读取并返回静态文件
 *
 * @param {string}               pathname 请求路径
 * @param {http.ServerResponse}  res      客户端响应
 */
function serveStatic(pathname, res) {
  const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const filePath = path.join(ROOT, relative);

  // 防止 ../ 越权读取 front 目录以外的文件
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403).end('Forbidden');
    return;
  }

  fs.readFile(filePath, (err, content) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(content);
  });
}

const server = http.createServer((req, res) => {
  if (req.url === '/api' || req.url.startsWith('/api/')) {
    proxy(req, res);
    return;
  }
  serveStatic(new URL(req.url, 'http://localhost').pathname, res);
});

server.listen(PORT, () => {
  console.log(`前端已启动：http://localhost:${PORT}`);
  console.log(`接口代理：/api -> http://${API_HOST}:${API_PORT}/api`);
});
