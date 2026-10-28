# 小米 12S ONNX 独立测速

测试包：`com.wakin.ladabench`，与原型 `com.wakin.mobilerepair.prototype` 分开。应用没有 INTERNET 权限，不读取相册，使用电脑生成的合成输入。

模型：Lada YOLO v4-fast（YOLO11n-seg，640×640，单帧）与 Lada BasicVSR++ generic v1.2 EMA（256×256，三帧）。模型来自官方权重，已校验 SHA-256，并在电脑对比 PyTorch 与 ONNX Runtime 输出。

CPU 后端：ONNX Runtime Android 1.31.1，intra-op 4 线程、inter-op 1 线程、ALL_OPT。没有注册 NNAPI/QNN 等加速后端。

每个模型会先检查手机输出与 PyTorch 参考输出是否满足 atol=1e-4、rtol=1e-3；不通过就停止该模型计时。

YOLO 预热 5 次，测量 20 次；BasicVSR++ 预热 2 次，测量 5 次。另有一次不计时的正确性检查推理。

两个计时口径：

- `total_ms`：tensor 创建 + session.run + 所有输出复制 + 资源关闭，与旧原型的测速边界接近。
- `session_run_ms`：只测 session.run。

不包含模型加载、文件 IO、视频解码、预处理、YOLO 的图外 NMS/掩膜后处理、跟踪、修复区域合成、编码或 UI。BasicVSR++ 的每帧摊销耗时是三帧调用耗时除以 3，不是单帧显示延迟，也不能当成完整视频 FPS。

记录原始样本、中位数、均值、p90、最小值、最大值，及每个模型开始和结束时的系统热状态、电池温度、PSS 与可用内存。这些内存点值不代表峰值。

准备模型与输入：在 WSL 的隔离 Python 环境运行 `scripts/export_detector_and_assets.py`。该步骤依赖已完成的 BasicVSR++ 导出和参考文件。

构建：

```powershell
.\scriptsuild_benchmark.ps1 -SdkPath '<Android SDK>' -JdkPath '<JDK 17>'
```

APK：`artifacts/phone-benchmark/lada-onnx-benchmark.apk`。

运行及取回结果：

```powershell
adb install artifacts/phone-benchmark/lada-onnx-benchmark.apk
adb shell am start -n com.wakin.ladabench/.BenchmarkActivity --ez autorun true
adb shell run-as com.wakin.ladabench cat files/benchmark-results.json
```

程序结束会保留独立测速应用和其结果文件。不会自动卸载软件或删除手机已有内容。
