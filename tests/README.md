# 小队回归测试

- `npm test` 检查小队创建、分摊、币种、旧账本清理、同名席位互斥、HTTPS 登记和附近补传。
- `node tests/run-desktop-flow.mjs` 在隔离用户目录启动 Electron。加 `--packaged` 可测试便携 EXE。检查创建旅行、记账、二维码、清理旧账本和备用邀请码。
- `node tests/run-android-sync.mjs` 在指定 Android 模拟器运行隔离测试包。Windows 小队检查使用 Android 原生客户端与桌面同步服务实际建立 TCP 会话，检查成员登记、双向流水和重连去重。Android 10 镜像需启动虚拟 Wi-Fi；可通过 `ANDROID_TEST_SERIAL` 选择设备。
- 真机发现、跨 Wi-Fi 首次登记和附近补传步骤见 [同步说明](../docs/android-peer-sync.md)。

旧版测试和报告随 [历史版本](https://github.com/eurTy/ride-ledger/releases) 留存。
