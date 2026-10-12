# hongguoTV

红果短剧 TV 版 —— 热更新分发仓库（Cloudflare Workers 静态资源托管）。

> 本仓库**只存放分发文件**，不存放源码。

## 目录结构

```
├── wrangler.jsonc          # Cloudflare 部署配置（指明静态资源目录）
├── README.md               # 本说明
└── public/                 # 静态资源目录（对外可直接访问）
    ├── update.json         # 版本清单
    └── hongguo-<版本>.apk  # 热更新包
```

部署后访问路径（域名由 Cloudflare 提供）：

- `https://<你的域名>/update.json`
- `https://<你的域名>/hongguo-1.0.0.apk`

## update.json 格式

```json
{
  "versionCode": 1,
  "versionName": "1.0.0",
  "apk": "hongguo-1.0.0.apk",
  "notes": "更新说明"
}
```

| 字段 | 说明 |
|---|---|
| `versionCode` | 整数。App 的 versionCode 小于它才会提示更新 |
| `versionName` | 展示用版本号 |
| `apk` | 包文件名，与 `update.json` 同在 `public/` 下 |

## 发版方式

1. 构建新版本 APK
2. 复制到 `public/`，命名 `hongguo-<版本>.apk`，**删除旧包**
3. 修改 `public/update.json` 的 `versionCode` / `versionName` / `apk`
4. `git push`

Cloudflare 会自动重新部署，几秒后新版本即可访问。

## Cloudflare 配置要点

本项目类型为 **Workers（静态资源）**，不是 Pages。部署命令 `npx wrangler deploy`
依赖仓库根目录的 `wrangler.jsonc`：

```jsonc
{
  "name": "hongguotv",
  "compatibility_date": "2026-10-12",
  "assets": { "directory": "./public" }
}
```

- 纯静态资源**不需要** `main` 字段（无需 Worker 脚本）
- 若报 `Could not detect a directory containing static files`，说明 `wrangler.jsonc`
  的 `assets.directory` 没配对，或文件不在该目录下

### 配额（Workers 免费版）

| 项 | 上限 |
|---|---|
| 单个文件 | 25 MiB |
| 文件数 / 版本 | 20,000 |
| 静态资源请求 | 免费不限量 |
