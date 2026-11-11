/**
 * 假后端（仅用于本地验证代理转发，可随时删除）
 * 用法： node mock-backend.mjs   （监听 8666，与真实后端同端口）
 */
import { createServer } from 'http';

createServer((req, res) => {
  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', () => {
    const record = {
      method: req.method,
      // 真实后端 context-path 是 /api，所以这里应该能看到完整路径
      url: req.url,
      'content-type': req.headers['content-type'],
      cookie: req.headers.cookie || '(无)',
      body: body || '(空)',
    };
    console.log(JSON.stringify(record, null, 2));
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ code: 0, data: record, message: 'ok' }));
  });
}).listen(8666, () => console.log('mock backend on 8666'));
