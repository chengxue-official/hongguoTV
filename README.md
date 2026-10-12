# hongguoTV

红果短剧 TV 版 —— 热更新分发仓库。

> 本仓库**只存放分发文件**，不存放源码。源码在本地开发仓库。

## 文件说明

| 文件 | 作用 |
|---|---|
| `update.json` | 版本清单，App 启动/点击时读取，与本地 versionCode 比对 |
| `hongguo-<版本>.apk` | 热更新包 |

## update.json 格式

```json
{
  "versionCode": 1,
  "versionName": "1.0.0",
  "apk": "hongguo-1.0.0.apk",
  "notes": "更新说明"
}
```

- `versionCode`：整数，App 的版本号小于它才会提示更新
- `apk`：包文件名，与 `update.json` 同目录

## 发版方式

1. 构建新版本 APK
2. 把 APK 复制到本仓库，命名 `hongguo-<版本>.apk`，删除旧包
3. 修改 `update.json` 的 `versionCode` / `versionName` / `apk`
4. `git push`

Cloudflare Pages 会自动部署，已被托管的域名下即刻可访问新文件。
