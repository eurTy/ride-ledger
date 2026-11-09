# 安卓回归测试

运行 `node tests/run-android-sync.mjs`，在指定 Android 模拟器中安装隔离测试包。覆盖组队登记、账目规则、双向 TCP 补传、断线与去重、删除传播和界面流程。

- 用 `ANDROID_TEST_SERIAL` 指定设备序列号。
- 模拟器无法通过虚拟网卡访问电脑时，设置 `ANDROID_TEST_ADB_REVERSE=1`，通过 ADB 转发测试端口。
- Wi-Fi、热点和蓝牙射频场景的双机验证见 [连接说明](../docs/android-peer-sync.md)。
