# Ride Ledger · 小队旅行账本

一次旅行一个账本。先在自己的设备记下现金、支出和换汇，附近连通后自动补传。各币种分别结算。

| 客户端 | 当前版本 | 加入及同步 |
| --- | --- | --- |
| [Windows x64](https://github.com/eurTy/ride-ledger/releases/tag/v0.2.0-test.9) | `0.2.0-test.9` | 显示二维码、从图片读取二维码、备用文字邀请码。同一 Wi-Fi 或热点下与安卓小队互通。 |
| [Android 10 及以上](https://github.com/eurTy/ride-ledger/releases/tag/v0.2.0-test.8) | `0.2.0-test.8` | 扫码、相册和备用文字邀请码。安卓设备之间还可走蓝牙。 |
| 网页试用 | 源码自带 | 本机试用小队记账、分摊和结算。 |

## 组队与记账

1. 一端新建小队，填写 1～5 个名字。其他人在「旅行 → 加入小队旅行」读取邀请二维码并选自己的名字。每个名字登记一台设备。
2. 首次登记时建队设备打开旅行。附近可使用同一 Wi-Fi 或热点。异地时由建队端在「跨 Wi-Fi 登记设置」填写双方都能访问的 HTTPS 中转，随后重新生成二维码。
3. 登记完成后，设备保存身份，重连无需再次扫码或输码。账目走附近连接，断线时先存在本机。打开同一趟旅行并恢复附近连接后自动补传，HTTPS 中转只处理首次登记。
4. 默认打开快速记账。现金余额、结算及完整流水集中在「复盘」，旅行管理和邀请集中在「旅行」。

## 升级与开发

- 新版只显示小队旅行。打开新版后，旧双人旅行、本机对应流水和偏好会被清理，小队账目继续使用。旧模式的安装包和源码可在 [GitHub 历史 Release](https://github.com/eurTy/ride-ledger/releases) 查看。
- Windows 便携 EXE 内含运行环境。开发时先运行 `npm install`，再运行 `npm run desktop` 或 `npm run desktop:package`。
- 安卓原生客户端使用 Java 与 SQLite。用 Android Studio 或 `./build-debug.ps1` 构建调试包。覆盖安装需要相同签名。
- 本地网页试用运行 `node sync-server/server.mjs`，在同一台电脑打开 [本地试用页](http://127.0.0.1:8787)。
- [连接及实机测试步骤](docs/android-peer-sync.md) · [回归测试说明](tests/README.md)
