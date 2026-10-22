# 大麦网络助手 Android V0.1

此目录是一个用于 Android / HarmonyOS 兼容设备的网络诊断原型，不是自动抢票机器人。

## 下载测试 APK

打开 [GitHub Actions 构建页面](https://github.com/wshuijian/alipay-listener/actions/workflows/build-damai-android-helper.yml)，进入最新构建记录，等待状态为 **Success**，再从页面底部的 **Artifacts** 下载 \`damai-android-helper-debug-apk\`。解压得到 \`app-debug.apk\` 后传到手机安装。

## 功能

- 检查当前网络类型与 Android 系统网络验证状态。
- 手动访问大麦南京站公开演出详情页，记录耗时、HTTP 状态或连接错误。
- 手动记录排队繁忙、请求异常、缺票等提示。
- 查看、复制、清空本地诊断记录。

## 限制

- 不读取大麦登录信息、Cookie 或内部 API。
- 不自动刷新或提交订单，不重放或修改购票请求，不绕过官方排队或风控。
- 公开详情页响应时间不代表内部排队服务延迟，也不能预测购票成功率。
- 这是未签名发布版的 debug APK（由 Android 构建流程自动 debug 签名），尚未在真实华为鸿蒙设备上验证。
