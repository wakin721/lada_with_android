"""Reproducible CPU export probes for the original Lada BasicVSR++ EMA model."""
from __future__ import annotations
import argparse
import collections
import importlib.metadata
import json
import os
import platform
import subprocess
import sys
import time
import traceback
from pathlib import Path
import numpy as np
import torch
from lada_export_model import (load_generator, enable_portable_deform,
                               register_onnx_deform_symbolic, portable_deform_conv2d,
                               UPSTREAM_COMMIT)

ROOT = Path(__file__).resolve().parents[1]


def sample_clips(frames, size):
    rng = np.random.default_rng(20261028)
    coarse = rng.random((1, frames, 3, 16, 16), dtype=np.float32)
    block = np.repeat(np.repeat(coarse, size // 16, -2), size // 16, -1)
    y, x = np.meshgrid(np.linspace(0, 1, size, dtype=np.float32),
                       np.linspace(0, 1, size, dtype=np.float32), indexing="ij")
    rgb = np.stack((x, y, (x + y) / 2))
    moving = np.stack([np.roll(rgb, t * 3, axis=-1) for t in range(frames)])[None]
    return {"block_noise": np.ascontiguousarray(block), "moving_gradient": np.ascontiguousarray(moving)}


def compare(actual, expected, atol, rtol):
    d = np.abs(actual.astype(np.float64) - expected.astype(np.float64))
    result = {"shape": list(actual.shape), "finite": bool(np.isfinite(actual).all()),
              "max_abs_error": float(d.max()), "mean_abs_error": float(d.mean()),
              "rmse": float(np.sqrt(np.mean(d ** 2))), "atol": atol, "rtol": rtol,
              "allclose": bool(np.allclose(actual, expected, atol=atol, rtol=rtol))}
    return result


def test_portable_operator():
    import torchvision
    torch.manual_seed(721)
    tests = []
    for channels, out_channels, groups, og, stride, padding, dilation in [
        (8, 6, 1, 2, 1, 1, 1), (8, 8, 2, 4, 2, 2, 2), (128, 64, 1, 16, 1, 1, 1)
    ]:
        n, h, w, k = 1, 9, 11, 3
        oh = (h + 2 * padding - dilation * (k - 1) - 1) // stride + 1
        ow = (w + 2 * padding - dilation * (k - 1) - 1) // stride + 1
        x = torch.rand(n, channels, h, w)
        weight = torch.randn(out_channels, channels // groups, k, k) * .03
        bias = torch.randn(out_channels) * .01
        offset = torch.randn(n, og * 2 * k * k, oh, ow) * 1.5
        mask = torch.sigmoid(torch.randn(n, og * k * k, oh, ow))
        with torch.inference_mode():
            expected = torchvision.ops.deform_conv2d(x, offset, weight, bias,
                           (stride, stride), (padding, padding), (dilation, dilation), mask)
            actual = portable_deform_conv2d(x, offset, weight, bias,
                           (stride, stride), (padding, padding), (dilation, dilation), mask)
        result = compare(actual.numpy(), expected.numpy(), 2e-5, 2e-4)
        result.update(channels=channels, out_channels=out_channels, groups=groups, offset_groups=og)
        tests.append(result)
        if not result["allclose"]:
            raise AssertionError(f"Portable DeformConv equivalence failed: {result}")
    return tests


def validate_onnx(path, clips, references):
    import onnx
    import onnxruntime as ort
    graph = onnx.load(path)
    onnx.checker.check_model(graph, full_check=True)
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    opts.inter_op_num_threads = 1
    session = ort.InferenceSession(str(path), opts, providers=["CPUExecutionProvider"])
    results = {}
    for name, clip in clips.items():
        start = time.perf_counter()
        actual = session.run(None, {session.get_inputs()[0].name: clip})[0]
        result = compare(actual, references[name], 1e-4, 1e-3)
        result["host_cpu_elapsed_seconds_single_run"] = time.perf_counter() - start
        results[name] = result
        if not result["allclose"]:
            raise AssertionError(f"ONNX output mismatch on {name}: {result}")
    return {"onnx_checker": "passed", "providers": session.get_providers(),
            "operators": dict(collections.Counter(n.op_type for n in graph.graph.node)),
            "nodes": len(graph.graph.node), "validation": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["reference", "onnx-native", "onnx-portable", "litert-native", "litert-portable"])
    parser.add_argument("--source", type=Path, default=ROOT / ".tools/lada")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "artifacts/weights/lada_mosaic_restoration_model_generic_v1.2.pth")
    parser.add_argument("--frames", type=int, default=3)
    parser.add_argument("--size", type=int, default=256)
    args = parser.parse_args()
    if args.frames < 3:
        parser.error("Use at least three frames to exercise second-order alignment")
    if args.size < 256 or args.size % 16:
        parser.error("Unmodified upstream network needs size >=256; probe requires a multiple of 16")
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    torch.manual_seed(721)
    directory = ROOT / "artifacts/exports" / f"t{args.frames}_{args.size}"
    directory.mkdir(parents=True, exist_ok=True)
    report_path = directory / f"{args.action}.json"
    report = {"action": args.action, "status": "started", "input_shape": [1,args.frames,3,args.size,args.size],
              "platform": platform.platform(), "python": platform.python_version(),
              "validation_scope": "two synthetic video clips; numerical correctness only, not restoration quality or phone performance",
              "threads": 4, "versions": {}}
    start = time.perf_counter()
    for name in ("torch", "torchvision", "mmengine", "onnx", "onnxruntime", "litert-torch", "ai-edge-litert"):
        try: report["versions"][name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError: pass
    def save():
        report["elapsed_seconds"] = time.perf_counter() - start
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    try:
        commit = subprocess.check_output(["git", "-c", "core.filemode=false", "-c", "core.autocrlf=true", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
        if commit != UPSTREAM_COMMIT:
            raise ValueError(f"Unexpected source revision: {commit}")
        dirty = subprocess.check_output(["git", "-c", "core.filemode=false", "-c", "core.autocrlf=true", "-C", str(args.source), "status", "--porcelain"], text=True).strip()
        if dirty: raise ValueError("Upstream checkout has changes; refusing ambiguous source provenance")
        print("Loading original EMA generator", flush=True)
        model, info = load_generator(args.source, args.checkpoint)
        report.update(info)
        clips = sample_clips(args.frames, args.size)
        reference_path = directory / "reference.npz"
        if args.action == "reference":
            outputs = {}
            for name, clip in clips.items():
                then = time.perf_counter()
                with torch.inference_mode(): outputs[name] = model(torch.from_numpy(clip)).numpy()
                if not np.isfinite(outputs[name]).all(): raise ValueError("Non-finite reference output")
                print(name, "reference seconds", time.perf_counter() - then, flush=True)
            np.savez_compressed(reference_path, **outputs)
            report["status"] = "reference_saved"
            report["output_shapes"] = {name:list(out.shape) for name,out in outputs.items()}
        else:
            if not reference_path.exists(): raise FileNotFoundError("Run reference action first")
            with np.load(reference_path) as data: references = {k:data[k] for k in data.files}
            if "portable" in args.action:
                report["portable_operator_tests"] = test_portable_operator()
                enable_portable_deform()
                report["portable_model_validation"] = {}
                for name, clip in clips.items():
                    with torch.inference_mode(): output = model(torch.from_numpy(clip)).numpy()
                    result = compare(output, references[name], 1e-4, 1e-3)
                    report["portable_model_validation"][name] = result
                    if not result["allclose"]: raise AssertionError(f"Portable full-model mismatch: {result}")
                save()
                print("Portable operator and full-model equivalence passed", flush=True)
            sample = torch.from_numpy(clips["block_noise"])
            if args.action.startswith("onnx"):
                path = directory / f"lada-generic-v1.2-{args.action}.onnx"
                register_onnx_deform_symbolic(19)
                print("Exporting", path, flush=True)
                with torch.inference_mode():
                    torch.onnx.export(model, (sample,), str(path), input_names=["frames"],
                                      output_names=["restored"], opset_version=19, dynamo=False,
                                      do_constant_folding=True, external_data=False)
                report["status"] = "exported_unvalidated"
                report["model_file"] = path.name
                report["model_bytes"] = path.stat().st_size
                save()
                print("Validating with ONNX checker and ONNX Runtime CPU", flush=True)
                report.update(validate_onnx(path, clips, references))
                report["status"] = "verified"
            else:
                import litert_torch
                path = directory / f"lada-generic-v1.2-{args.action}.tflite"
                print("Converting using LiteRT Torch", flush=True)
                with torch.no_grad(): edge = litert_torch.convert(model, (sample,))
                edge.export(str(path))
                report["status"] = "exported_unvalidated"
                report["model_file"] = path.name
                report["model_bytes"] = path.stat().st_size
                save()
                from ai_edge_litert.interpreter import Interpreter
                interpreter = Interpreter(model_path=str(path), num_threads=4)
                interpreter.allocate_tensors()
                input_detail = interpreter.get_input_details()[0]
                output_detail = interpreter.get_output_details()[0]
                report["validation"] = {}
                for name, clip in clips.items():
                    interpreter.set_tensor(input_detail["index"], clip)
                    interpreter.invoke()
                    output = interpreter.get_tensor(output_detail["index"])
                    result = compare(output, references[name], 1e-4, 1e-3)
                    report["validation"][name] = result
                    if not result["allclose"]: raise AssertionError(f"LiteRT mismatch: {result}")
                report["status"] = "verified"
        save()
        print(json.dumps(report, indent=2), flush=True)
    except Exception as error:
        report["status"] = "failed"
        report["error_type"] = type(error).__name__
        report["error"] = str(error)
        report["traceback"] = traceback.format_exc()
        save()
        traceback.print_exc()
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
