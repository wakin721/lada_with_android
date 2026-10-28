# 手机原型模型核查与 Android 推理方案

核查日期：2026-10-28。设备：用户连接的小米 12S。操作仅包含 ADB 只读查询和把 APK 复制到电脑，未安装、启动、卸载应用或修改手机数据。

## 已验证的手机原型

- 包名：`com.wakin.mobilerepair.prototype`；版本：`0.1`。
- APK 内置修复模型：`assets/mobile_deblock_fp32.onnx`，275,369 bytes。
- SHA-256：`40d138da4c47093fa19eeb9932b507838f2f8404fcfcb4ff462814557b588490`。
- 已通过 `onnx.checker.check_model` 检查。
- 输入 `image`、输出 `restored` 均为 FLOAT32 `[1, 3, height, width]`。
- 参数总数 66,924；算子：Conv 14、Relu 7、Constant 6、Mul 6、Add 7、DepthToSpace 1。
- APK 中仅发现该 ONNX 模型，没有 YOLO 或 BasicVSR++ 权重。
- 反编译 `RestoreEngine` 显示使用 ONNX Runtime、CPU 四线程、单 inter-op 线程、ALL_OPT 图优化；未注册 NNAPI、XNNPACK 或 QNN 执行提供程序。
- 源代码先预热 3 次、再测量 12 次并取中位数；计时包含 tensor 创建、推理和输出复制，不含裁剪、缩放、视频解码或 UI。
- 用户提供的 128 为 5.4 ms、256 为 21.1 ms 是该原型的报告数据；本次没有重新运行测速，也未取得原始样本 JSON。
- 因此不能把这组耗时当作 Lada BasicVSR++ 的移动端性能。
- 应用不是 debuggable，`run-as` 读取私有目录被拒绝；本次未尝试 root 访问。

## 与上游 Lada 的差别

上游 Lada 的 BasicVSR++ 输入为视频序列 `[N,T,C,H,W]`，包含 SPyNet 光流、双向特征传播及 `torchvision.ops.deform_conv2d`。手机原型是小型单帧去块网络，结构不相同；仅根据文件后缀不能确定模型来源或质量。

核查所读上游代码：
- https://github.com/ladaapp/lada/blob/main/lada/models/basicvsrpp/inference.py
- https://github.com/ladaapp/lada/blob/main/lada/models/basicvsrpp/mmagic/basicvsr_plusplus_net.py

## YOLO 与修复网络均使用 ONNX 是否会加速

可以共用 ONNX Runtime 依赖，但仍然是两个独立模型和推理会话。统一 ONNX 格式本身不保证提速，也不会自动融合检测、裁剪与修复。

实际速度取决于模型结构、输入大小、数据类型、算子覆盖、执行提供程序、设备驱动、预处理/后处理与传输。CPU、XNNPACK、NNAPI、QNN 不能混称为同一种加速路径；注册执行提供程序也不代表整张图都在该硬件上运行。

若把 YOLO 从较慢的执行路径迁移到经过优化的 ONNX Runtime，可能降低检测时间；不会直接降低修复网络的单次推理时间。减少检测频率并配合跟踪也需要评估漏检和区域漂移。

## 后续落地顺序

1. 选择 Lada 对应的检测与修复权重，固定版本与输入契约，先在电脑验证输出。
2. 导出 YOLO ONNX 并验证预处理、类别、输出布局以及分割掩膜；不能把普通目标检测器当成马赛克检测器。
3. 对真正的 BasicVSR++ 验证视频窗口、光流采样和可变形卷积的导出及 Android runtime 支持，不能假定一次 export 就能移动端运行。
4. 建立手机 ONNX CPU 基线，再分别评估受支持的加速后端；记录实际算子分配和输出误差。
5. 若 ONNX 后端不能满足要求，再对同模型、同输入比较 LiteRT GPU/NPU。转换成功和输出正确是测性能的前置条件。
6. 对实际视频统计解码、检测、跟踪、修复、合成、编码各阶段，并进行持续运行和多修复区域测试。

参考：
- https://onnxruntime.ai/docs/execution-providers/
- https://onnxruntime.ai/docs/tutorials/mobile/
- https://docs.ultralytics.com/integrations/onnx/
- https://developers.google.com/edge/litert/android

## 当前仓库状态

已从用户提供的 GitHub 地址克隆到当前目录；远端是无提交的空仓库。本次生成本报告及本地忽略规则，尚未实现 Android 界面或 Lada 移植。APK、提取模型、模型结构 JSON、反编译引擎保存在被 Git 忽略的 `artifacts/device/`，用于本地核查；没有上传手机 APK 或模型，也没有推送代码。
