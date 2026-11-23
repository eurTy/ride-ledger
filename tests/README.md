# 安卓回归测试

运行 `node tests/run-android-sync.mjs`，在指定 Android 模拟器中安装隔离测试包。覆盖组队登记、账目规则、双向 TCP 补传、断线与去重、删除传播和界面流程。

- 用 `ANDROID_TEST_SERIAL` 指定设备序列号。
- 模拟器无法通过虚拟网卡访问电脑时，设置 `ANDROID_TEST_ADB_REVERSE=1`，通过 ADB 转发测试端口。
- 在具备 BLE 扫描和广播能力的隔离设备上启用蓝牙，用 `adb install -g` 安装测试包后，运行 `adb shell am instrument -w -e bleRecovery true com.rideledger.synctest/com.rideledger.SyncTestInstrumentation`。此项专项测试模拟系统停止扫描后刷新，以及重复启动失败后的退避重试。
- Wi-Fi、热点和蓝牙射频场景的双机验证见 [连接说明](../docs/android-peer-sync.md)。
