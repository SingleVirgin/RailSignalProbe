# RailSignalProbe — 高铁信号采集工具（Android）

记录 GPS 轨迹 + 信号强度 + 网络类型 + 基站信息，JSONL 格式，用于高铁隧道网络优化项目的实车数据验证。

对应研究文档：`高铁隧道网络优化App可行性研究.md`、`V1模块设计-围栏与预加载.md`（见上层目录）。

## 云端构建（GitHub Actions）

1. 在 GitHub 新建一个**公开仓库**（免费、Actions 无限额度）；
2. 本机推送：

```bash
cd RailSignalProbe
git init
git add .
git commit -m "init: rail signal probe v0.1"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

3. 推送后进入仓库 **Actions** 页，工作流 `Build APK` 自动运行（约 3~5 分钟）；
4. 构建完成后在该次运行页底部 **Artifacts** 下载 `RailSignalProbe-debug`，解压得到 APK；
5. 传到手机安装（需允许"安装未知来源应用"）。

> 注意：工作流会自动生成 Gradle wrapper（`gradle wrapper --gradle-version 8.7`），工程里无需提交 wrapper 文件。产物为 debug 签名，可直接安装测试。

## 采集产物

- 路径：`/sdcard/Android/data/com.railsignal.probe/files/probe_<时间戳>.jsonl`
- 每行一个 JSON：时间戳、lat/lon/speed/accuracy、网络类型（NR/LTE_CA/LTE/…）、服务状态、SA/NSA 标识、信号强度、注册小区（PCI/CI/EARFCN）
- 可用 `adb pull` 或手机文件管理器导出

## 使用步骤

1. 安装并打开 App，授予定位、电话、通知权限；
2. 点"开始采集"（前台服务通知出现，可熄屏）；
3. 高铁行程结束后点"停止"，导出 JSONL 文件。

## 系统要求

- Android 8.0（API 26）及以上
- 需要 GPS / 网络定位可用
