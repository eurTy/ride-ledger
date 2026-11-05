# iPhone 版本与 TestFlight

**2026-11-05 已按用户要求暂停并封档。** 源码保留在 `codex/ios-testflight` 分支，封档标签为 `archive-ios-2026-11-05`；状态快照见 [archive-status.json](archive-status.json)。本分支未合入 `main`，安卓和 Windows 仍沿用 v0.1.4。

封档时 47 项共用协议及 iOS 接入回归已通过；两次 GitHub 苹果构建均未分配到 runner，未进入代码编译。第一轮队列提示 macOS ARM 构建机容量不足。Swift 编译、6 项苹果平台 XCTest、设备签名及 TestFlight 上传均未验证；当前没有可安装的 iOS 发布包或 TestFlight 邀请链接。

iOS 构建已移除 push/PR 自动触发，构建与上传 job 都显式停用，手动触发也会跳过。恢复时先切回此分支，对照源码提交 `d6ff087d0bd77de9e1f1db47b9b430186d3498d0` 恢复需要的触发条件及 job，再完成苹果环境构建验证。未恢复前不继续排队、重试、签名或上传。

本目录是 iOS 客户端，不是可直接安装的 IPA 下载。界面与金额计算复用已有网页代码；Swift 原生部分提供本机受保护文件、同热点 Bonjour/TCP 同步、系统分享、复制和备份导出。它使用系统 WebKit，网页资源全部随应用打包，断网可打开和记账。

最低运行版本为 iOS 16；构建使用 iOS 26 或更新 SDK。现有安卓最低版本、Windows 安装包及数据目录保持原样。

## 当前发布条件

目前用户没有 Apple Developer 付费账号，也没有 Mac。因此本轮可准备源码、执行云端构建和模拟器验证，不能生成可分发签名或开放 TestFlight 邀请。

不必为构建购买 Mac。公开仓库可使用标准 GitHub macOS runner；真正上传 TestFlight 仍需本人加入 Apple Developer Program。苹果官方费用为每年 99 美元或当地货币等值金额：

- [开发者计划注册](https://developer.apple.com/help/account/membership/program-enrollment)
- [TestFlight 流程](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)
- [提交时的 SDK 要求](https://developer.apple.com/news/upcoming-requirements/)
- [GitHub 标准公开仓库 runner](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run)

## 源码与构建

已有 Node.js 即可在 Windows 准备 Xcode 工程及资源：

```powershell
npm run ios:prepare
```

生成的网页资源、1024 像素不透明图标、虚构互通测试夹具在 `ios/generated/`，不提交个人数据。Mac 上打开 `ios/RideLedger.xcodeproj`，选择 `RideLedger` scheme。

GitHub 的 **iOS build and simulator checks** 在标准 `macos-15` 环境选择已安装的稳定版 Xcode 26 或更新版本，构建并执行 iPhone 模拟器检查，不需要苹果账号。检查使用模拟器本地签名；设备归档明确不签名，不能安装到真实 iPhone 或上传 TestFlight。结果包保存在 Actions 的 `ios-build-and-test-results`，保留 14 天。

已编写的 XCTest 用例覆盖原生文件保存与重开、损坏写入隔离、WKWebView 解密 Node.js 生成的账本、原始资金及多币种金额、删除去重、离线补记与重开、邀请码更新后保留身份、原生长度帧 TCP 交换；这些苹果平台用例在封档时尚未运行。测试中的 HTTPS 地址路由使用替身响应；实体 iPhone 的公网接入、本地网络授权与热点发现仍需真机检查。测试成功执行时会将 iPhone 模拟器界面截图附在 XCTest 结果中，目前没有已验证截图。

## 数据与同步

- 账本保存在应用的 Application Support/RideLedger/ledger.json。写入先校验、后原子替换，并启用首次解锁后文件保护。WebKit 缓存回收不会清除该文件。
- 读取失败会停止加载并提示保留应用数据，不会用空账本覆盖原文件。保存失败不会更新内存中的账目。
- 本机 HTTP 服务仅绑定 `127.0.0.1:18788`，只提供打包的静态资源，不提供账本文件或公网中转。原生接口校验主 frame 和本机来源，不允许跳转到外部网站。
- 同热点使用 `_rideledger._tcp` Bonjour 与四字节长度头 JSON；只交换加密快照。AES-GCM、旅行密钥、认证 token 与安卓和 Windows 一致。
- 跨网使用邀请码中的 HTTPS 地址；iPhone 自身不启动公网隧道。电脑关闭后可继续在手机离线记录，电脑恢复连接后再同步。临时地址变化时重新粘贴该旅行的新邀请码，身份和已有账目保留。
- 同步在前台运行；iOS 进入后台后不保证即时同步。首次授权本地网络用于离线发现同行，拒绝后仍可记账和使用 HTTPS 同步。
- “旅行”中可导出本机 JSON 备份。备份包含旅行密钥，应由使用者妥善保管；当前版本未提供备份导入界面。
- 本客户端没有广告、分析 SDK、定位或账号登录。应用可将加密快照发送到用户选择的服务；该服务及网络提供方仍能看到连接元数据。隐私清单已包含在应用中，App Store 隐私问卷应按最终使用的服务确认。

## 账号注册后，上传 TestFlight

1. 在 Apple Developer 注册后确认会员生效，并在 App Store Connect 接受协议。
2. 注册 Bundle ID `com.eurty.rideledger`，在 App Store Connect 创建对应 iOS 应用。这个标识尚未在苹果平台占用或保留；若不可用，需要在工程及构建脚本中统一修改。
3. 准备 Apple Distribution 证书及其私钥导出的 P12、App Store Connect 分发描述文件、App Store Connect API 密钥。没有 Mac 时，证书请求及 P12 也可通过 Windows 工具生成，后续可继续协助配置。
4. 在仓库的 `ios-testflight` Environment Secrets 中保存下表内容。不要上传证书私钥到源码、Release 或聊天；无需提供 Apple 账号密码。

| Secret | 内容 |
| --- | --- |
| `IOS_TEAM_ID` | 十位 Apple Team ID |
| `IOS_DISTRIBUTION_P12_BASE64` | 含私钥的 Apple Distribution P12 的 Base64 |
| `IOS_DISTRIBUTION_PASSWORD` | 该 P12 的密码 |
| `IOS_PROFILE_BASE64` | 对应 Bundle ID 的 App Store Connect 描述文件 Base64 |
| `ASC_KEY_ID` | 十位 App Store Connect API Key ID |
| `ASC_ISSUER_ID` | 对应团队的 Issuer ID |
| `ASC_PRIVATE_KEY` | AuthKey P8 的完整内容 |

5. 将流程合入默认分支后，打开 Actions → **Upload iOS to TestFlight** → Run workflow，填写递增的 build number。该流程只在手动触发时签名和上传，不在 PR 或普通 push 上访问这些密钥。
6. 上传后等待苹果处理，在 App Store Connect 填写测试说明、反馈邮箱、审核联系人和加密出口合规回答，再添加测试人员。外部测试的首次构建需经过苹果 Beta App Review，批准后才能获得公开测试邀请链接。

上传流程只负责归档、签名及传送文件，不会自动填写隐私与加密合规、邀请测试人员或声称已通过审核。未提供 `ITSAppUsesNonExemptEncryption` 默认值；需要账号持有人按实际使用的系统 AES-GCM、HMAC 和 HTTPS 回答苹果的问题。

`metadata/testflight.zh-Hans.json` 提供公开说明和审核操作草稿。真实审核联系人及电话号码不应写入公开仓库；可在 App Store Connect 填写，或以后通过私有 `ASC_CONTACT_JSON` 环境变量交给元数据工具，结构为 `{feedbackEmail, reviewContact:{firstName,lastName,email,phone}}`。`node ios/validate-metadata.mjs` 检查外部审核资料是否齐备；`--draft` 仅报告缺项，不阻止先上传构建。

签名流程只在 runner 临时目录写入证书及 API 私钥，使用临时钥匙串并在退出时清理；不会将它们打包进构建产物。带真实苹果账号的签名和上传尚未实测，需在账号开通后完成。
