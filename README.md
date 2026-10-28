# Lada Android

Material 3 Expressive 风格的本地视频马赛克修复应用，包名 `com.wakin.lada`。

当前支持：

- 系统文件选择器导入视频，播放原片，按时间取帧。
- 单击「自动识别并修复」即可检测并修复当前帧的全部候选区域，无需手动框选；另提供可选手动模式、修复前后拖动对比和保存 PNG。
- 连续自动检测与修复预览，显示候选框、实际处理 FPS 和缓冲状态，支持暂停、继续与时间定位。
- 原片全屏播放，以及处理后预览的全屏切换。
- CPU / WebGPU 开关。CPU 为默认且已验证的后端；WebGPU 为实验功能，可能有部分 CPU 回退，首次准备可能较慢。
- 原版 Lada 模式，以及在本机提供轻量模型时可选择的「轻量流畅」模式。两种模型画质不同，界面明确标注；轻量模式可选择 128×128 或 256×256 输入。

处理能力不足时会缓冲等待，不能承诺原版 Lada 在小米 12S 上达到原视频帧率。修复预览目前为静音；原片全屏播放包含原音轨。当前没有修复后整段视频的文件导出。

应用通过系统文件选择器获得所选视频权限，没有 INTERNET 或全盘存储权限。处理不会改写原视频。停止会立即结束播放状态，并用 ORT RunOptions 请求终止推理；未完成的旧任务隔离到后台释放，旧回调不能覆盖新播放。GPU 释放期间切回 CPU，结束后可重新开启。GPU 任务未正常结束后，下次启动回到 CPU。

## 构建 Android APK

工具：JDK 17、Android SDK Platform 37、Build Tools 37.0.0。Gradle Wrapper 固定为 9.7.1。

将 SDK 路径写入不入库的 `local.properties`，例如：

```properties
sdk.dir=D:/Android/Sdk
```

应用模型由 `artifacts/app-assets/` 提供，二进制模型与个人手机 APK 不在源码仓库中。先按下一节准备模型，再构建：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。ABI 为 arm64-v8a，最低 Android 9。

## 准备模型

在 Linux/WSL 的隔离 Python 环境中运行。现用导出环境为 Python 3.12、CPU PyTorch 2.14.0、torchvision 0.29.0；具体包见 `scripts/export-requirements.txt`。

```bash
python -m venv /var/tmp/lada-export-env
source /var/tmp/lada-export-env/bin/activate
pip install --no-compile torch==2.14.0+cpu torchvision==0.29.0+cpu --index-url https://download.pytorch.org/whl/cpu
pip install --no-compile -r scripts/export-requirements.txt
python scripts/bootstrap_models.py
python scripts/try_export.py reference
python scripts/try_export.py onnx-native
python scripts/try_export.py onnx-portable
python scripts/export_detector_and_assets.py
python scripts/export_live_detector.py
python scripts/prepare_app_assets.py
```

所有官方权重会检查公布的 SHA-256；导出后执行 ONNX checker 与实际推理的数值比较。应用在首次使用时再次检查打包模型的 SHA-256。

默认修复网络为 Lada generic v1.2 EMA，输入三帧 256×256，BGR float32。WebGPU 修复路径使用已验证的等价算子导出版本。检测为 Lada YOLO v4-fast（YOLO11n-seg），选帧用 640 输入，连续预览用 320 输入；两类马赛克候选均可识别。

可选轻量输入：本机 `artifacts/device/assets/mobile_deblock_fp32.onnx`。准备脚本仅在该文件存在且与已核查哈希一致时将其加入 APK；否则界面隐藏轻量选项，原版模式仍可用。此模型来自用户手机原型，公开仓库不提供该权重，且它不是原版 Lada。

## 验证与测速

Debug 构建可使用无个人内容的内置合成视频：

```powershell
adb shell am start -n com.wakin.lada/.MainActivity --ez demo true --ez restore true --ez manual true
adb shell am start -n com.wakin.lada/.MainActivity --ez demo true --ez live true --es quality fast --ez manual true
```

原版修复 ONNX、等价算子修复 ONNX、640/320 检测 ONNX 已通过电脑端实际推理验证。独立测速程序在 `benchmark/`。

小米 12S CPU 四线程基线：YOLO 640 中位数约 94.1 ms/帧；原版 BasicVSR++ 256 约 2003.5 ms/三帧，摊销约 667.8 ms/帧。这是合成输入的模型测试，包含 tensor 创建和输出复制，不是完整视频 FPS。详细方法、原始样本及限制见 `docs/xiaomi12s-onnx-benchmark.md`。

## 模型来源

原版修复与检测模型来自 [ladaapp/lada](https://github.com/ladaapp/lada) 和官方 [Hugging Face 模型库](https://huggingface.co/ladaapp/lada)。模型遵循 AGPL-3.0，原许可随模型打包。当前导出没有执行剪枝或蒸馏。LiteRT 实验脚本独立于 Android 推理路径，当前应用使用 ONNX。
