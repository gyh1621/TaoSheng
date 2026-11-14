# Typeless 手机麦克风让出时序修复（第二版）

## 修改
- 保持包名 app.resound；versionCode 78，versionName 0.3.0-micfix.2。
- 将本轮见过的竞争录音事件锁存，不会被紧随其后的 stop 回调覆盖。
- 录音配置回调通过 StateFlow 立即唤醒看门狗，停止阻塞读取，再在原有 finally 中释放。回调线程不操作 AudioRecord，避免关闭竞态；保留 250 ms 轮询兜底。
- 让麦后每 250 ms 检查一次，连续检查 2 秒空闲再恢复，给输入法失败后重试留出时间。
- 不加权限、网络请求或输入法内容读取；不修改数据库/数据迁移。

## 限制
- 这是对用户“多点几次才成功”的时序改进，不是已由手机日志证实的唯一根因。
- Android 公开录音配置接口不是其他应用录音请求前的拦截器。若 Typeless 在注册录音之前失败，仍可能没有任何可观察事件，不能保证首次点击成功。
- 自动让麦及额外空闲窗口会造成录音缺口，不能称为连续收音。
- 真机需检查：首击/重试、Typeless 完成后恢复、手动暂停不会被恢复、锁屏持续录音及其他输入设备。
- 同上一版测试签名时可覆盖上一版测试包，不能覆盖作者官方签名版本。

## 验证
- 共用业务边界脚本通过。
- iOS 检查脚本未找到 Xcode，未执行 iOS 编译。
- 8 个相关单测通过（MicHandoff 7、MicYield 1），独立代码复核未发现阻断项。
- assembleRelease 成功；apksigner v2 验证、zipalign 检查通过。签名证书与第一版测试包一致。
- APK SHA256：56f8a676f347eec0b5303ae209f6774462491a0a56f9267ac4ed0ded647997d4。
- `:shared:check` 通过，192 个 JVM 测试零失败，shared lint 通过。
- `:app:lintRelease` 已运行但未通过：3 个错误、283 个警告、21 个提示。三个错误位于未修改的上游文件：`ClassModeTileAndroid.kt:62`（StartActivityAndCollapseDeprecated）、`ui/today/ListenSheet.kt:97`（StateFlowValueCalledInComposition）、`ui/onboarding/OnboardingTips.kt:38`（UnusedContentLambdaTargetStateParameter）。已核对这三个文件相对上游基线未变动，没有绕过 lint 或改动 UI 来掩盖结果。
- 首次构建曾出现 daemon 消失，低并发分拆运行后完成上述编译、测试与报告。
