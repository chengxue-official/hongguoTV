/**
 * HougTime 日志托管 Worker
 * ------------------------------------------------------------------
 * 背景：App 的「一键上传日志」原本只能把全量日志塞进飞书自定义机器人
 * 的 markdown 卡片正文。飞书 webhook **不支持发文件消息**，日志一长就
 * 被切成多张长卡片，接收端只能手动滚动复制、痛苦且易漏。
 *
 * 本 Worker 提供「文件化」落点：
 *   POST /api/log            上传日志文本 -> 存 KV，返回一次性 token 与链接
 *   GET  /log/<token>        在线查看页（手机上直接看，带复制/下载按钮）
 *   GET  /log/<token>.txt    纯文本下载（Content-Disposition: attachment）
 *
 * 其余请求原样交给静态资源（update.json / APK 分发不受影响）。
 *
 * 存储与时效（两条策略）：
 * 1. **限流**：同一设备 15 分钟内最多上传一次（防手抖连点 / 刷屏）。
 *    以请求体的 deviceKey 为维度（App 端持久化的安装 ID），老版本未带时
 *    回退用机型名。命中限流返回 429 + 剩余秒数。
 * 2. **未被读取即清理**：上传时只给 24 小时 TTL——若一直没人查看/下载，
 *    24 小时后自动删除，不占空间。一旦被「在线查看」或「下载」，续期到
 *    30 天（看过的日志留得久一点，方便回头对比）。
 *
 * KV 约束：单 value ≤ 25 MiB（App 端日志上限约 1 MB，安全）。
 */

const PREFIX = 'log:';
const RATE_PREFIX = 'rate:';

/** 未被查看/下载时的存活时间：24 小时后自动删除 */
const TTL_UNREAD_SECONDS = 60 * 60 * 24;
/** 被查看/下载后的续期时长：30 天 */
const TTL_READ_SECONDS = 60 * 60 * 24 * 30;
/** 同一设备的上传间隔：15 分钟 */
const RATE_WINDOW_SECONDS = 15 * 60;

const MAX_BYTES = 3 * 1024 * 1024;     // 单次上传上限 3 MB
const TOKEN_RE = /^[a-z0-9]{10}$/;

/** 生成短 token（10 位 base36，约 51.7 bit 熵，不可枚举）。 */
function newToken() {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let out = '';
  for (let i = 0; i < 10; i++) {
    out += ALPHABET[bytes[i % bytes.length] % ALPHABET.length];
  }
  return out;
}

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

function byteLen(s) {
  return new TextEncoder().encode(s).length;
}

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

/** 剩余限流秒数；0 表示可以上传。 */
async function rateRemain(env, deviceKey) {
  const raw = await env.LOGS.get(RATE_PREFIX + deviceKey, 'text');
  if (!raw) return 0;
  const last = parseInt(raw, 10) || 0;
  if (!last) return 0;
  const remain = RATE_WINDOW_SECONDS - (nowSeconds() - last);
  return remain > 0 ? remain : 0;
}

/** 记一次上传（写限流时间戳，TTL 与窗口等长，过期自动清理）。 */
async function rateTouch(env, deviceKey) {
  try {
    await env.LOGS.put(RATE_PREFIX + deviceKey, String(nowSeconds()), {
      expirationTtl: RATE_WINDOW_SECONDS,
    });
  } catch (e) {
    // 限流写入失败不应让上传失败（软限制）
  }
}

/** 上传：接收 JSON {device, deviceKey?, time, content, title?}，落 KV，返回链接。 */
async function handleUpload(request, env) {
  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json({ code: 400, msg: '请求体不是合法 JSON' }, 400);
  }

  const content = typeof body.content === 'string' ? body.content : '';
  if (!content) {
    return json({ code: 400, msg: 'content 不能为空' }, 400);
  }
  const size = byteLen(content);
  if (size > MAX_BYTES) {
    return json({ code: 413, msg: `日志过大（${size} 字节，上限 ${MAX_BYTES}）` }, 413);
  }

  // 限流维度：优先 deviceKey（设备唯一安装 ID），老版本回退机型名
  const deviceKey = String(body.deviceKey || body.device || '').trim().slice(0, 64);
  if (deviceKey) {
    const remain = await rateRemain(env, deviceKey);
    if (remain > 0) {
      const mins = Math.ceil(remain / 60);
      return json({
        code: 429,
        msg: `上传过于频繁，请约 ${mins} 分钟后再试`,
        retryAfter: remain,
      }, 429);
    }
  }

  const token = newToken();
  const record = {
    content,
    device: String(body.device || '').slice(0, 120),
    deviceKey,
    time: String(body.time || '').slice(0, 60),
    title: String(body.title || '').slice(0, 200),
    size,
    uploadedAt: new Date().toISOString(),
    readAt: null,
  };

  try {
    // 关键：只给 24 小时 TTL。无人查看/下载的日志会自动清掉；
    // 被读取时由 markRead() 续期到 30 天。
    await env.LOGS.put(PREFIX + token, JSON.stringify(record), {
      expirationTtl: TTL_UNREAD_SECONDS,
    });
  } catch (e) {
    return json({ code: 500, msg: '存储失败：' + (e && e.message ? e.message : e) }, 500);
  }

  if (deviceKey) await rateTouch(env, deviceKey);

  const origin = new URL(request.url).origin;
  return json({
    code: 0,
    token,
    size,
    view: `${origin}/log/${token}`,
    download: `${origin}/log/${token}.txt`,
  });
}

/** 读取 KV 记录；不存在返回 null。 */
async function readRecord(env, token) {
  const raw = await env.LOGS.get(PREFIX + token, 'text');
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (e) {
    return { content: raw, device: '', time: '', title: '', size: byteLen(raw), readAt: null };
  }
}

/**
 * 标记「已被读取」：把 TTL 从 24 小时续到 30 天。
 * 幂等——已标记过的直接跳过，避免每次打开查看页都重写 3MB 的 value。
 */
async function markRead(env, token, rec) {
  if (!rec || rec.readAt) return;
  rec.readAt = new Date().toISOString();
  try {
    await env.LOGS.put(PREFIX + token, JSON.stringify(rec), {
      expirationTtl: TTL_READ_SECONDS,
    });
  } catch (e) {
    // 续期失败不影响本次读取（日志内容已经拿到了）
  }
}

/** 纯文本下载：attachment 触发「保存文件」，文件名带设备与时间便于区分。 */
async function handleDownload(env, token) {
  const rec = await readRecord(env, token);
  if (!rec) {
    return new Response('日志不存在或已被清理（未被查看的日志保留 1 天）', {
      status: 404,
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    });
  }
  // 下载即视为「已读取」：续期到 30 天
  await markRead(env, token, rec);

  const dev = (rec.device || 'device').replace(/[^\w\u4e00-\u9fa5.-]+/g, '_').slice(0, 40);
  const stamp = (rec.time || rec.uploadedAt || '').replace(/[^\d]/g, '').slice(0, 14);
  const filename = `hougtime-log-${dev}-${stamp || 'now'}.txt`;
  return new Response(rec.content, {
    status: 200,
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      'Content-Disposition':
        `attachment; filename="${filename}"; filename*=UTF-8''${encodeURIComponent(filename)}`,
      'Cache-Control': 'no-store',
    },
  });
}

/** 在线查看页：手机上直接看，一键复制 / 一键下载。 */
async function handleView(env, token) {
  const rec = await readRecord(env, token);
  if (!rec) {
    return new Response(
      `<!doctype html><html lang="zh-CN"><meta charset="utf-8">
       <meta name="viewport" content="width=device-width,initial-scale=1">
       <title>日志不存在</title>
       <body style="font-family:system-ui,-apple-system,'PingFang SC',sans-serif;
                    padding:40px 20px;text-align:center;color:#111;background:#f6f7f9">
       <h2 style="margin:0 0 12px">日志不存在或已被清理</h2>
       <p style="color:#666;font-size:14px;line-height:1.7">
         未被查看/下载的日志会在上传 <b>24 小时</b> 后自动清理。<br>
         请让电视端重新点一次「上传日志」。</p>
       </body></html>`,
      { status: 404, headers: { 'Content-Type': 'text/html; charset=utf-8' } }
    );
  }

  // 打开查看页即视为「已读取」：续期到 30 天，避免正看着就被清掉
  await markRead(env, token, rec);

  const sizeKb = (rec.size / 1024).toFixed(1);
  const metaLine = [
    rec.device ? `设备 ${esc(rec.device)}` : '',
    rec.time ? `时间 ${esc(rec.time)}` : '',
    `${sizeKb} KB`,
  ].filter(Boolean).join(' · ');

  const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>HougTime 日志 · 在线查看</title>
<style>
  *{box-sizing:border-box}
  body{margin:0;background:#f6f7f9;color:#111;
       font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;
       -webkit-text-size-adjust:100%}
  header{position:sticky;top:0;z-index:2;background:#fff;border-bottom:1px solid #e6e8eb;
         padding:14px 16px}
  h1{margin:0 0 4px;font-size:17px;font-weight:600}
  .meta{color:#6b7280;font-size:12.5px;line-height:1.5}
  .actions{display:flex;gap:10px;margin-top:12px;flex-wrap:wrap}
  .btn{flex:1 1 0;min-width:130px;display:flex;align-items:center;justify-content:center;
       height:40px;border-radius:9px;border:1px solid #d7dbe0;background:#fff;color:#111;
       font-size:14px;font-weight:500;text-decoration:none;cursor:pointer;
       -webkit-tap-highlight-color:transparent}
  .btn.primary{background:#1f6feb;border-color:#1f6feb;color:#fff}
  .btn:active{opacity:.82}
  main{padding:12px 16px 40px}
  pre{margin:0;background:#fff;border:1px solid #e6e8eb;border-radius:10px;padding:14px;
      font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;
      line-height:1.6;white-space:pre-wrap;word-break:break-all;overflow-wrap:anywhere;
      max-height:none;color:#1f2328}
  .hint{color:#6b7280;font-size:12.5px;line-height:1.7;margin:0 0 10px}
  #toast{position:fixed;left:50%;bottom:32px;transform:translateX(-50%);background:rgba(17,17,17,.9);
         color:#fff;font-size:13px;padding:9px 16px;border-radius:20px;opacity:0;
         transition:opacity .2s;pointer-events:none;z-index:9;white-space:nowrap}
  #toast.on{opacity:1}
</style>
</head>
<body>
<header>
  <h1>HougTime 日志</h1>
  <div class="meta">${metaLine}</div>
  <div class="actions">
    <button class="btn primary" id="copy">一键复制全文</button>
    <a class="btn" id="dl" href="/log/${esc(token)}.txt" download>下载 .txt 文件</a>
  </div>
</header>
<main>
  <p class="hint">在电脑上打开本页可「下载」为文件；手机上点「一键复制全文」即可整段粘贴给开发者，无需手动滚动选择。</p>
  <pre id="body">加载中…</pre>
</main>
<div id="toast"></div>
<script>
(function(){
  var token = ${JSON.stringify(token)};
  var bodyEl = document.getElementById('body');
  var toastEl = document.getElementById('toast');
  var t = null;
  function toast(msg){
    toastEl.textContent = msg; toastEl.classList.add('on');
    clearTimeout(t); t = setTimeout(function(){ toastEl.classList.remove('on'); }, 1800);
  }
  fetch('/log/' + token + '.txt', { cache: 'no-store' })
    .then(function(r){ if(!r.ok) throw new Error('HTTP ' + r.status); return r.text(); })
    .then(function(text){ bodyEl.textContent = text; })
    .catch(function(e){ bodyEl.textContent = '日志加载失败：' + e.message + '\\n可直接点上方「下载 .txt 文件」。'; });

  document.getElementById('copy').addEventListener('click', function(){
    var text = bodyEl.textContent || '';
    function fallback(){
      try{
        var ta = document.createElement('textarea');
        ta.value = text; ta.style.position='fixed'; ta.style.top='-1000px';
        document.body.appendChild(ta); ta.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(ta);
        toast(ok ? '已复制全文' : '复制失败，请点「下载 .txt 文件」');
      }catch(err){ toast('复制失败，请点「下载 .txt 文件」'); }
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function(){ toast('已复制全文'); }, fallback);
    } else { fallback(); }
  });
})();
</script>
</body>
</html>`;

  return new Response(html, {
    status: 200,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // 预检（原生 App 不触发，留给可能的网页端调用）
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type',
        },
      });
    }

    if (path === '/api/log') {
      if (request.method !== 'POST') return json({ code: 405, msg: '请用 POST' }, 405);
      return handleUpload(request, env);
    }

    const m = path.match(/^\/log\/([A-Za-z0-9]{4,32})(\.txt)?$/);
    if (m) {
      const token = m[1].toLowerCase();
      if (!TOKEN_RE.test(token)) {
        return new Response('链接格式不正确', { status: 400, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
      }
      return m[2] ? handleDownload(env, token) : handleView(env, token);
    }

    // 其余请求交给静态资源（/update.json、APK 等）
    if (env.ASSETS) return env.ASSETS.fetch(request);
    return new Response('Not Found', { status: 404 });
  },
};
