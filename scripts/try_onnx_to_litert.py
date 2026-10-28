"""Alternative ARM64 conversion probe using onnx2tf's direct FlatBuffer backend.

No custom LiteRT kernels are permitted; exported files must execute with the
standard LiteRT interpreter and match the original PyTorch reference clips.
"""
from __future__ import annotations
import argparse
import collections
import importlib.metadata
import json
import os
import time
import traceback
from pathlib import Path
import numpy as np
from try_export import ROOT, sample_clips, compare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("variant", choices=["native", "portable"])
    parser.add_argument("--frames", type=int, default=3)
    parser.add_argument("--size", type=int, default=256)
    args = parser.parse_args()
    base = ROOT / "artifacts/exports" / f"t{args.frames}_{args.size}"
    source = base / f"lada-generic-v1.2-onnx-{args.variant}.onnx"
    output = base / f"litert-via-onnx-{args.variant}"
    output.mkdir(parents=True, exist_ok=True)
    report_path = base / f"litert-via-onnx-{args.variant}.json"
    report = {"status": "started", "onnx_source": str(source), "backend": "flatbuffer_direct",
              "custom_ops_allowed": False, "models": {}, "versions": {},
              "validation_scope": "two synthetic clips, not restoration quality or phone speed"}
    start = time.perf_counter()
    for package in ("onnx2tf", "onnx", "onnxruntime", "numpy", "ai-edge-litert"):
        report["versions"][package] = importlib.metadata.version(package)
    def save():
        report["elapsed_seconds"] = time.perf_counter() - start
        report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    try:
        os.environ.setdefault("TQDM_DISABLE", "1")
        import onnx2tf
        print("Converting", source.name, "to standard LiteRT builtins", flush=True)
        onnx2tf.convert(input_onnx_file_path=str(source), output_folder_path=str(output),
                        tflite_backend="flatbuffer_direct", flatbuffer_direct_allow_custom_ops=False,
                        keep_shape_absolutely_input_names=["frames"],
                        not_use_onnxsim=True, not_use_opname_auto_generate=True,
                        report_op_coverage=True, non_verbose=True)
        report["status"] = "exported_unvalidated"
        save()
        from ai_edge_litert.interpreter import Interpreter
        from ai_edge_litert import schema_py_generated as schema
        with np.load(base / "reference.npz") as data:
            references = {name: data[name] for name in data.files}
        clips = sample_clips(args.frames, args.size)
        files = sorted(output.glob("*.tflite"))
        if not files: raise RuntimeError("Converter returned without a LiteRT model")
        for path in files:
            graph = schema.Model.GetRootAsModel(path.read_bytes(), 0)
            builtin_codes = {getattr(schema.BuiltinOperator, k):k for k in dir(schema.BuiltinOperator)
                             if isinstance(getattr(schema.BuiltinOperator, k), int)}
            codes = [graph.OperatorCodes(i).BuiltinCode() for i in range(graph.OperatorCodesLength())]
            if schema.BuiltinOperator.CUSTOM in codes:
                raise AssertionError("Output contains custom kernels and is not portable")
            then = time.perf_counter()
            interpreter = Interpreter(model_path=str(path), num_threads=4)
            interpreter.allocate_tensors()
            inp = interpreter.get_input_details()[0]
            out = interpreter.get_output_details()[0]
            result = {"model_bytes": path.stat().st_size,
                      "input_shape": inp["shape"].tolist(), "output_shape": out["shape"].tolist(),
                      "builtin_operator_types": sorted({builtin_codes.get(x,str(x)) for x in codes}),
                      "validation": {}}
            is_half = "float16" in path.name
            for name, clip in clips.items():
                if list(clip.shape) != inp["shape"].tolist():
                    raise ValueError(f"Unexpected input layout: {inp['shape']}")
                interpreter.set_tensor(inp["index"], clip.astype(inp["dtype"]))
                interpreter.invoke()
                actual = interpreter.get_tensor(out["index"])
                if actual.shape != references[name].shape:
                    raise ValueError(f"Unexpected output layout: {actual.shape}")
                metrics = compare(actual, references[name], 2e-3 if is_half else 1e-4,
                                   2e-2 if is_half else 1e-3)
                result["validation"][name] = metrics
                if not metrics["allclose"]:
                    report["models"][path.name] = result
                    raise AssertionError(f"LiteRT numerical mismatch: {path.name}, {name}: {metrics}")
            result["host_validation_elapsed_seconds"] = time.perf_counter() - then
            report["models"][path.name] = result
            save()
        report["status"] = "verified"
        save()
        print(json.dumps(report, indent=2), flush=True)
        return 0
    except BaseException as error:
        report["status"] = "failed"
        report["error_type"] = type(error).__name__
        report["error"] = str(error)
        report["traceback"] = traceback.format_exc()
        save()
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
