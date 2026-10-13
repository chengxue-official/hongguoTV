/**
 * Worker 本地自测：不依赖 Cloudflare，用内存 KV 模拟 env.LOGS，
 * 覆盖上传 / 下载 / 查看页 / 404 / 静态资源透传 / 非法 token / 超大体积。
 */
import worker from '../src/index.js';

function makeEnv() {
  const store = new Map();
  return {
    store,
    LOGS: {
      async put(key, value, opts) { store.set(key, { value, opts }); },
      async get(key) { const e = store.get(key); return e ? e.value : null; },
    },
    ASSETS: {
      async fetch(req) {
        return new Response('STATIC:' + new URL(req.url).pathname, {
          headers: { 'Content-Type': 'text/plain' },
        });
      },
    },
  };
}

let pass = 0, fail = 0;
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra ? ' -> ' + extra : '')); }
}

const env = makeEnv();
const base = 'https://hongguo.kdns.fr';

// 1. 上传
console.log('1) POST /api/log 上传');
const content = '===== 设备信息 =====\n型号: test-box\n\n===== 日志 =====\n' + 'x'.repeat(500) + '\n';
const upResp = await worker.fetch(new Request(base + '/api/log', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ device: 'TestBox', time: '10-13 20:00:00', content }),
}), env);
const up = await upResp.json();
check('HTTP 200', upResp.status === 200, upResp.status);
check('code=0', up.code === 0, JSON.stringify(up));
check('token 10 位', typeof up.token === 'string' && up.token.length === 10, up.token);
check('view 链接以 /log/ 开头', up.view === base + '/log/' + up.token, up.view);
check('download 以 .txt 结尾', up.download === base + '/log/' + up.token + '.txt', up.download);
check('KV 已写入且带 TTL', env.store.has('log:' + up.token) && env.store.get('log:' + up.token).opts.expirationTtl === 2592000);

// 2. 下载
console.log('2) GET /log/<token>.txt 下载');
const dlResp = await worker.fetch(new Request(base + '/log/' + up.token + '.txt'), env);
const dlText = await dlResp.text();
check('HTTP 200', dlResp.status === 200);
check('正文与上传一致', dlText === content);
check('Content-Disposition 为 attachment',
  (dlResp.headers.get('Content-Disposition') || '').includes('attachment'),
  dlResp.headers.get('Content-Disposition'));
check('文件名含 hongguo-log 与设备名',
  (dlResp.headers.get('Content-Disposition') || '').includes('hougtime-log-TestBox'),
  dlResp.headers.get('Content-Disposition'));

// 3. 查看页
console.log('3) GET /log/<token> 查看页');
const viewResp = await worker.fetch(new Request(base + '/log/' + up.token), env);
const viewHtml = await viewResp.text();
check('HTTP 200', viewResp.status === 200);
check('Content-Type 为 html',
  (viewResp.headers.get('Content-Type') || '').includes('text/html'));
check('含「一键复制全文」按钮', viewHtml.includes('一键复制全文'));
check('含下载链接', viewHtml.includes('/log/' + up.token + '.txt'));
check('含设备信息', viewHtml.includes('TestBox'));
check('token 已正确注入脚本', viewHtml.includes('var token = "' + up.token + '"'));

// 4. 不存在的 token
console.log('4) 不存在的 token');
const missDl = await worker.fetch(new Request(base + '/log/aaaaaaaaaa.txt'), env);
check('下载 404', missDl.status === 404, missDl.status);
const missView = await worker.fetch(new Request(base + '/log/aaaaaaaaaa'), env);
check('查看页 404', missView.status === 404, missView.status);
check('查看页 404 有中文提示', (await missView.text()).includes('不存在或已过期'));

// 5. 非法 token 格式
console.log('5) 非法 token');
const bad = await worker.fetch(new Request(base + '/log/TOO_LONG_TOKEN_$$$'), env);
const badText = await bad.text();
check('落到静态资源而非日志路由', badText.startsWith('STATIC:'), badText);

// 6. 静态资源透传
console.log('6) 静态资源透传（update.json / APK 不受影响）');
const u = await worker.fetch(new Request(base + '/update.json'), env);
check('/update.json -> 静态资源', (await u.text()) === 'STATIC:/update.json');
const a = await worker.fetch(new Request(base + '/hongguo-1.0.4.apk'), env);
check('/hongguo-1.0.4.apk -> 静态资源', (await a.text()) === 'STATIC:/hongguo-1.0.4.apk');

// 7. 边界：空 content / 非 JSON / 超大
console.log('7) 边界用例');
const empty = await worker.fetch(new Request(base + '/api/log', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ content: '' }),
}), env);
check('空 content -> 400', empty.status === 400, empty.status);
const notJson = await worker.fetch(new Request(base + '/api/log', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: 'not-json',
}), env);
check('非 JSON -> 400', notJson.status === 400, notJson.status);
const big = await worker.fetch(new Request(base + '/api/log', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ content: 'a'.repeat(4 * 1024 * 1024) }),
}), env);
check('超 3MB -> 413', big.status === 413, big.status);
const getApi = await worker.fetch(new Request(base + '/api/log'), env);
check('GET /api/log -> 405', getApi.status === 405, getApi.status);

// 8. OPTIONS 预检
console.log('8) OPTIONS 预检');
const opt = await worker.fetch(new Request(base + '/api/log', { method: 'OPTIONS' }), env);
check('204 + CORS 头', opt.status === 204 && opt.headers.get('Access-Control-Allow-Origin') === '*', opt.status);

console.log('\n结果：' + pass + ' 通过 / ' + fail + ' 失败');
if (fail > 0) process.exit(1);
