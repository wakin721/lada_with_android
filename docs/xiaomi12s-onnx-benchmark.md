# 小米 12S ONNX 实测

设备：小米 12S（2206123SC / SM8475），Android SDK 37。运行时：ONNX Runtime Android 1.31.1，CPU 四线程，inter-op 一线程，ALL_OPT。未注册 GPU/NPU 执行提供程序。

| 模型与输入 | 每次帧数 | 总耗时中位数 ms/次 | session.run 中位数 ms/次 | 摊销总耗时 ms/帧 | 总耗时范围 ms/次 |
|---|---:|---:|---:|---:|---:|
| YOLO v4-fast 640 | 1 | 94.051 | 89.752 | 94.051 | 88.842–96.525 |
| BasicVSR++ generic v1.2 T3 256 | 3 | 2003.529 | 2001.299 | 667.843 | 1967.148–2008.651 |

总耗时包含 tensor 创建、模型推理、所有输出复制和资源关闭；不含模型加载、解码、预处理、图外 NMS/掩膜、跟踪、区域合成、编码或 UI。三帧摊销耗时不等于单帧显示延迟。不能把这些数字直接称为完整软件视频 FPS。

YOLO：Lada v4-fast，实际为 YOLO11n-seg，输入 1×3×640×640，预热 5 次，测量 20 次。原始输出分别为 1×38×8400 与 1×32×160×160。

BasicVSR++：generic v1.2 原始 EMA 权重，固定输入 1×3×3×256×256，包含二阶时序对齐。预热 2 次，测量 5 次。没有剪枝、蒸馏或量化。

两个模型在手机上均已通过与电脑 PyTorch 参考结果的逐元素对比（atol=1e-4、rtol=1e-3），没有非有限输出或超出容差的元素。使用合成输入，不读取用户媒体；该检查不能替代画质评估。

测试开始/结束系统 thermal_status 均为 0，电池温度从 31.0°C 到 32.0°C；USB 连接、充电状态、电量 100%。这是一次较短的基线测试，不代表长时间持续性能。内存记录为阶段前后的点值，不是峰值。

原始样本与正确性数据：`../artifacts/phone-benchmark/xiaomi12s-cpu-results.json`。

独立测速包 `com.wakin.ladabench` 已安装，原型 `com.wakin.mobilerepair.prototype` 未覆盖。测试程序保留在手机上，没有自动卸载或删除手机文件。

## GPU 执行提供程序可行性

- QNN GPU：ONNX Runtime 官方支持以 `libQnnGpu.so` 作为 QNN GPU 后端，支持浮点权重与激活；需要相应的 Android QNN EP 和 Qualcomm SDK 运行库。当前未配置 QNN/QAIRT SDK；本次只在 `/vendor/lib64` 范围发现 OpenCL 和一个 HTP stub，没有发现 `libQnnGpu.so`。这不代表芯片不能使用 QNN GPU，只说明不能直接依赖该目录启动。
- 原生 WebGPU EP：官方文档列出 Android 经 Dawn 使用 Vulkan。本机 `/vendor/lib64/hw/vulkan.adreno.so` 存在，但有 Vulkan 驱动不等于已验证 Dawn/WebGPU 可用。已进一步确认现用 Android 1.31.1 AAR 内置 WEBGPU，具备直接测试入口；仍需要实际适配器、算子分配和输出验证。
- BasicVSR++ 的原始 ONNX 包含 8 个 DeformConv；另一个已验证的等价 ONNX 已把它们替换为 GridSample/Conv。这一版本是 GPU 适配的候选，不意味着已经 GPU 加速或必然更快。
- GPU 测试必须记录算子分配、CPU 回退和传输开销，并重新验证输出。当前没有 QNN GPU 或 WebGPU 的手机耗时数据。

官方资料：
- https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html

补充：WebGPU 首次测试没有得到完整样本，报告仍为 running；应用退出记录包含用户移除任务和后台低内存回收。因此尚不能把这次未完成测试归因于 WebGPU 不支持，也没有可报告的 GPU 速度。用户随后要求优先完成预览界面和开关。
