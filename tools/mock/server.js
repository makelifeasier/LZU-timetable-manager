// 本地样本服务：单进程、显式 Content-Length。
// （用 python -m http.server 时 Windows Store 版 python 会派生子进程，
//   残留进程抢同一端口会把响应体截断，导致 ERR_CONTENT_LENGTH_MISMATCH。）
//
// 两个用途：
//  1) 给解析器/端到端测试提供样本页
//  2) 模拟教务系统的「课表页」路由，用来验证 App 的自动发现链路
//     （.../showTimetable.do?... 一律返回课表样本，无论 id/yearid/termid 是什么）
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || '.';
const port = Number(process.argv[3] || 8080);

// 模拟教务系统：只要路径是课表页，就返回课表样本。
// 这样即使 App 拼出的是「去掉 id 的兜底链接」，也能拿到课表，便于验证兜底路径。
// 第 4 个参数可换成别的文件，用来验证「页面抓到了但里面没有课表」这条分支：
//   node tools/mock/server.js tools/mock 8080 notimetable.u8.html
const TIMETABLE_SAMPLE = process.argv[4] || 'mock-timetable.u8.html';


http.createServer((req, res) => {
  let rel = decodeURIComponent((req.url || '/').split('?')[0]);
  console.log(new Date().toISOString().slice(11, 23), req.method, req.url);

  if (rel.includes('showTimetable')) rel = '/' + TIMETABLE_SAMPLE;

  const rootDir = path.resolve(root);
  // 必须 strip 掉前导 '/' 再 resolve：
  // path.join('serve', '/a.html') 仍是相对路径，而 path.resolve('serve', '/a.html')
  // 会跳到盘符根目录，两种写法都会让下面的越权校验误判成 403。
  const file = path.resolve(rootDir, rel.replace(/^\/+/, ''));
  if (file !== rootDir && !file.startsWith(rootDir + path.sep)) {
    res.writeHead(403); res.end('forbidden'); return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found: ' + rel);
      return;
    }
    // 与教务系统一致：默认声明 GBK，让 App 的解码路径贴近真实；
    // *.u8.html 用 UTF-8（本次 mock 用，避免造 GBK 文件）
    const type = file.endsWith('.u8.html') ? 'text/html; charset=UTF-8'
      : file.endsWith('.html') ? 'text/html; charset=GBK'
        : 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': data.length });
    res.end(data);
  });
}).listen(port, '127.0.0.1', () => {
  console.log(`serving ${path.resolve(root)} on http://127.0.0.1:${port}`);
});
