# 简课

简洁的离线 Android 课程表，固定竖屏，只有「今日日程」「课表」两个主页面。首次启动没有课程。
仅适配重庆人工智能学院课表。

## 安装与使用

从 GitHub Releases 下载 APK，发送到手机后安装。支持 Android 8.0 及以上。

- 左右滑动课表翻周，点击周次选择指定周，「本周」返回当前周。
- 「＋」新增课程；点击课程可编辑或删除该上课安排。
- 「⋯」设置学期、导入或导出 `.xlsx`。
- 首次启动以当前周一作为第1周，请先在学期设置中填写实际开学日期。
- 导入前显示预览；确认后替换课程安排，包括手动修改，保留学期日期和作息。导入失败保留原数据。

应用无需账号，不申请网络权限。课程保存在本机，隐私说明见 [PRIVACY.md](PRIVACY.md)。

## 导入 Excel 格式
从[学习通](https://346799.v.chaoxing.com)导出EXCEL排课信息后导入。

按表头读取：课程编号、课程名称、任课教师、上课时间、上课教室；教学班编号可选。
支持连续周次、混合周次、单双周，以及多组时间与对应教室。
例如 `5,7-12周： 周五 7-8节,6周： 周二 10-11节`。
多组教室按相同顺序对应；一间教室可复用，数量有歧义时拒绝导入。
不支持 `.xls`、PDF 或图片。

[导入示例](examples/sample-timetable.xlsx)及测试数据全部虚构。
编辑和删除仅作用于所选上课安排的适用周次，保留同一课程的其他安排。

## 构建与测试

工具：JDK 17、Android SDK 35、Gradle 8.11.1。可使用 Android Studio 和 Gradle Wrapper。
Windows 便携环境：

```powershell
.\scripts\setup-tools.ps1
.\scripts\build.ps1
```

默认构建调试 APK 并运行单元测试，产物在 `dist/简课.apk`。
中文路径由脚本临时映射盘符，结束后恢复。依赖仓库见 `settings.gradle.kts`。

```powershell
.\scripts\build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','assembleDebugAndroidTest')
.\scripts\test-mumu.ps1 -Port 16384
```

设备测试使用虚构样本，结束后恢复测试前数据。详细范围见 [测试说明](docs/测试说明.md)。
发布构建使用 `assembleRelease`，签名密钥由发布者本地保管，不包含在源码中。
自行编译的 APK 与官方发布签名可能不同，不能直接覆盖安装。

## 目录

- `app/src/main/`：界面、课程逻辑、Excel 解析与 Room 本地存储。
- `app/src/test/`、`app/src/androidTest/`：单元与设备测试。
- `app/src/testFixtures/assets/`、`examples/`：虚构课表示例。
- `scripts/`：工具配置、构建和 MuMu 测试。

本地输入资料、工具缓存、安装包、日志和签名密钥均排除在版本控制之外。
