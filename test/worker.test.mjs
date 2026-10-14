/**
 * Worker 本地自测：不依赖 Cloudflare，用内存 KV 模拟 env.LOGS，
 * 覆盖上传 / 下载 / 查看页 / 404 / 静态资源透传 / 非法 token / 超大体积
 * 以及本轮新增的「15 分钟限流」与「未读取 24h 自动清理」两条策略。
 */
import worker from '../src/index.js';

const TTL_UNREAD = 60 * 60 * 24;        // 86400
const TTL_READ = 60 * 60 * 24 * 30;     // 2592000
const RATE_WINDOW = 15 * 60;            // 900

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

const base = 'https://hongguo.kdns.fr';
const post = (body, env) => worker.fetch(new Request(base + '/api/log', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
}), env);

// ========== 1. 上传基础 ==========
console.log('1) POST /api/log 上传');
const env = makeEnv();
const content = '===== 设备信息 =====\n型号: test-box\n\n===== 日志 =====\n' + 'x'.repeat(500) + '\n';
const upResp = await post({ device: 'TestBox', deviceKey: 'dev-aaa', time: '10-14 18:00:00', content }, env);
const up = await upResp.json();
check('HTTP 200', upResp.status === 200, upResp.status);
check('code=0', up.code === 0, JSON.stringify(up));
check('token 10 位', typeof up.token === 'string' && up.token.length === 10, up.token);
check('view/download 链接正确',
  up.view === base + '/log/' + up.token && up.download === base + '/log/' + up.token + '.txt', up.view);
check('上传时 TTL = 24 小时（未读取即清理）',
  env.store.get('log:' + up.token).opts.expirationTtl === TTL_UNREAD,
  env.store.get('log:' + up.token).opts.expirationTtl);
check('限流键已写入且 TTL = 15 分钟',
  env.store.has('rate:dev-aaa') && env.store.get('rate:dev-aaa').opts.expirationTtl === RATE_WINDOW,
  env.store.get('rate:dev-aaa') && env.store.get('rate:dev-aaa').opts.expirationTtl);

// ========== 2. 15 分钟限流 ==========
console.log('2) 15 分钟限流（同设备）');
const again = await post({ device: 'TestBox', deviceKey: 'dev-aaa', content: 'second' }, env);
const againBody = await again.json();
check('同设备 15 分钟内二次上传 -> 429', again.status === 429, again.status);
check('返回 code=429 与剩余秒数', againBody.code === 429 && againBody.retryAfter > 0 && againBody.retryAfter <= RATE_WINDOW,
  JSON.stringify(againBody));
check('提示文案含「频繁」', (againBody.msg || '').includes('频繁'), againBody.msg);
const otherDev = await post({ device: 'OtherBox', deviceKey: 'dev-bbb', content: 'third' }, env);
check('不同设备不受影响 -> 200', otherDev.status === 200, otherDev.status);
const noKey1 = await post({ content: 'nokey-1' }, env);
const noKey2 = await post({ content: 'nokey-2' }, env);
check('无 deviceKey/device 时不限流（两次都 200）', noKey1.status === 200 && noKey2.status === 200,
  noKey1.status + '/' + noKey2.status);

// 限流窗口过期后可再传（模拟时间流逝：直接删限流键）
env.store.delete('rate:dev-aaa');
const afterWindow = await post({ device: 'TestBox', deviceKey: 'dev-aaa', content: 'after window' }, env);
check('15 分钟窗口过后可再次上传 -> 200', afterWindow.status === 200, afterWindow.status);

// ========== 3. 下载（续期到 30 天） ==========
console.log('3) GET /log/<token>.txt 下载');
const dlResp = await worker.fetch(new Request(base + '/log/' + up.token + '.txt'), env);
const dlText = await dlResp.text();
check('HTTP 200', dlResp.status === 200);
check('正文与上传一致', dlText === content);
check('Content-Disposition 为 attachment',
  (dlResp.headers.get('Content-Disposition') || '').includes('attachment'));
check('文件名含设备名',
  (dlResp.headers.get('Content-Disposition') || '').includes('hougtime-log-TestBox'));
check('下载后 TTL 续期到 30 天',
  env.store.get('log:' + up.token).opts.expirationTtl === TTL_READ,
  env.store.get('log:' + up.token).opts.expirationTtl);
check('记录已打上 readAt 标记',
  JSON.parse(env.store.get('log:' + up.token).value).readAt !== null);

// 幂等：再次下载不应重复写（TTL 保持 30 天，且不报错）
const dlResp2 = await worker.fetch(new Request(base + '/log/' + up.token + '.txt'), env);
check('重复下载仍 200（幂等）', dlResp2.status === 200, dlResp2.status);
check('重复下载后 TTL 仍为 30 天',
  env.store.get('log:' + up.token).opts.expirationTtl === TTL_READ);

// ========== 4. 查看页（同样续期） ==========
console.log('4) GET /log/<token> 查看页');
const env2 = makeEnv();
const up2 = await (await post({ device: 'ViewBox', deviceKey: 'dev-view', content: 'view test' }, env2)).json();
check('新上传 TTL = 24 小时', env2.store.get('log:' + up2.token).opts.expirationTtl === TTL_UNREAD);
const viewResp = await worker.fetch(new Request(base + '/log/' + up2.token), env2);
const viewHtml = await viewResp.text();
check('HTTP 200', viewResp.status === 200);
check('Content-Type 为 html', (viewResp.headers.get('Content-Type') || '').includes('text/html'));
check('含「一键复制全文」', viewHtml.includes('一键复制全文'));
check('含下载链接', viewHtml.includes('/log/' + up2.token + '.txt'));
check('token 已注入脚本', viewHtml.includes('var token = "' + up2.token + '"'));
check('打开查看页后 TTL 续期到 30 天',
  env2.store.get('log:' + up2.token).opts.expirationTtl === TTL_READ,
  env2.store.get('log:' + up2.token).opts.expirationTtl);

// ========== 5. 未读取的日志：TTL 保持 24h（模拟过期被清） ==========
console.log('5) 未读取的日志 24h 后自动清理');
const env3 = makeEnv();
const up3 = await (await post({ device: 'ColdBox', deviceKey: 'dev-cold', content: 'never read' }, env3)).json();
check('上传后 TTL 为 24 小时', env3.store.get('log:' + up3.token).opts.expirationTtl === TTL_UNREAD);
check('未读取时 readAt 为 null',
  JSON.parse(env3.store.get('log:' + up3.token).value).readAt === null);
// 模拟 KV 到期自动删除
env3.store.delete('log:' + up3.token);
const goneView = await worker.fetch(new Request(base + '/log/' + up3.token), env3);
check('过期后查看页 404', goneView.status === 404, goneView.status);
check('404 文案说明 24 小时清理', (await goneView.text()).includes('24 小时'));
const goneDl = await worker.fetch(new Request(base + '/log/' + up3.token + '.txt'), env3);
check('过期后下载 404', goneDl.status === 404, goneDl.status);

// ========== 6. 不存在的 token ==========
console.log('6) 不存在的 token');
const missDl = await worker.fetch(new Request(base + '/log/aaaaaaaaaa.txt'), env);
check('下载 404', missDl.status === 404, missDl.status);
const missView = await worker.fetch(new Request(base + '/log/aaaaaaaaaa'), env);
check('查看页 404', missView.status === 404, missView.status);

// ========== 7. 合法 token 格式校验 ==========
console.log('7) 非法 token 格式');
const bad = await worker.fetch(new Request(base + '/log/TOO_LONG_TOKEN_$$$'), env);
check('落到静态资源', (await bad.text()).startsWith('STATIC:'));

// ========== 8. 静态资源透传 ==========
console.log('8) 静态资源透传');
const u = await worker.fetch(new Request(base + '/update.json'), env);
check('/update.json -> 静态资源', (await u.text()) === 'STATIC:/update.json');
const a = await worker.fetch(new Request(base + '/hongguo-1.0.6.apk'), env);
check('/hongguo-1.0.6.apk -> 静态资源', (await a.text()) === 'STATIC:/hongguo-1.0.6.apk');

// ========== 9. 边界用例 ==========
console.log('9) 边界用例');
check('空 content -> 400', (await post({ content: '' }, makeEnv())).status === 400);
const notJson = await worker.fetch(new Request(base + '/api/log', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: 'not-json',
}), env);
check('非 JSON -> 400', notJson.status === 400, notJson.status);
check('超 3MB -> 413', (await post({ content: 'a'.repeat(4 * 1024 * 1024) }, makeEnv())).status === 413);
check('GET /api/log -> 405',
  (await worker.fetch(new Request(base + '/api/log'), env)).status === 405);
check('超长 deviceKey 被截断后仍可用（不抛错）',
  (await post({ deviceKey: 'k'.repeat(200), content: 'x' }, makeEnv())).status === 200);

// ========== 10. OPTIONS ==========
console.log('10) OPTIONS 预检');
const opt = await worker.fetch(new Request(base + '/api/log', { method: 'OPTIONS' }), env);
check('204 + CORS 头', opt.status === 204 && opt.headers.get('Access-Control-Allow-Origin') === '*', opt.status);

console.log('\n结果：' + pass + ' 通过 / ' + fail + ' 失败');
if (fail > 0) process.exit(1);
