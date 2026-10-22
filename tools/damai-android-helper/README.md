# 大麦 APP 页面诊断 Android V0.2

面向 Android / HarmonyOS 兼容设备的原生诊断原型。**不是自动抢票程序**。

## 下载测试 APK

打开 [GitHub Actions 构建页面](https://github.com/wshuijian/alipay-listener/actions/workflows/build-damai-android-helper.yml)，进入最新的 **Build Damai Android Helper APK** 运行记录，等待状态为 **Success**，再下载 Artifacts 中的 `damai-android-helper-debug-apk`。解压得到 `app-debug.apk` 后传到手机安装。

## V0.1 与 V0.2

V0.1 的主要动作是请求公开演出详情页，容易让人误以为它在检测排队服务。V0.2 不再把网页响应测试作为主功能，而是用用户主动启用的 Android 无障碍服务观察大麦 APP（包名 `cn.damai`）的前台 UI。

## 使用步骤

1. 安装本项目生成的 `app-debug.apk`。
2. 打开“大麦 APP 页面诊断”。
3. 点击“开启 / 检查页面观察权限”，在系统无障碍设置里手动开启“大麦 APP 页面观察”。
4. 返回诊断工具，确认状态显示“已开启”。
5. 点击“打开大麦 APP”，由你自己操作官方购票流程。
6. 发生提示后，回到诊断工具查看日志，或用“复制诊断报告”分享诊断信息。

## 只观察，不操作

- 仅响应包名为 `cn.damai` 的无障碍事件。
- 只把识别到的少量提示类别、时间和网络类型写入本地记录，不保存页面全文或截图。
- 不自动点击、不自动刷新、不填写个人信息、不提交订单。
- 不读取或重放购票接口，不绕过队列、验证码或平台风控。
- 无障碍服务只能识别大麦 UI 暴露给无障碍树的文字；如果页面使用不可读取的自绘内容，可能检测不到。
- 识别不到提示不代表服务器正常，也不表示能提升排队优先级。

## 构建

JDK 17、Android SDK Platform 35、Gradle 8.9。源码在本目录内，可用 Android Studio 打开项目根目录。
