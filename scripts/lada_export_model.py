"""Load the unmodified pinned Lada generator and its inference EMA weights.

No training modules, GUI, user media or Android package are needed.
The upstream source remains separate under .tools/lada with its original licenses.
"""
from __future__ import annotations
import hashlib
import importlib
import importlib.machinery
import sys
import types
from pathlib import Path
import torch
import torch.nn.functional as F

EXPECTED_SHA256 = "d404152576ce64fb5b2f315c03062709dac4f5f8548934866cd01c823c8104ee"
UPSTREAM_COMMIT = "20cb34a20a83c72c87a991d2c949032c70085b16"


def load_generator(source: Path, checkpoint: Path):
    digest = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError(f"Checkpoint checksum mismatch: {digest}")
    # Namespace packages bypass only __init__ imports of GUI/dataset/training code.
    # The actual generator, optical flow and deformation code are upstream files.
    for name in ("lada", "lada.models", "lada.models.basicvsrpp", "lada.models.basicvsrpp.mmagic"):
        module = types.ModuleType(name)
        module.__path__ = [str(source.joinpath(*name.split(".")))]
        module.__package__ = name
        module.__spec__ = importlib.machinery.ModuleSpec(name, loader=None, is_package=True)
        sys.modules[name] = module
    net_module = importlib.import_module("lada.models.basicvsrpp.mmagic.basicvsr_plusplus_net")
    model = net_module.BasicVSRPlusPlusNet(mid_channels=64, num_blocks=15, spynet_pretrained=None)
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    if "state_dict" in state:
        state = state["state_dict"]
    # RealBasicVSR.forward_tensor selects generator_ema for inference.
    prefix = "generator_ema."
    if not any(k.startswith(prefix) for k in state):
        raise ValueError("Official checkpoint has no generator_ema weights; refusing silent fallback")
    selected = {k[len(prefix):]: v for k, v in state.items() if k.startswith(prefix)}
    model.load_state_dict(selected, strict=True)
    model.eval()
    return model, {
        "upstream_commit": UPSTREAM_COMMIT,
        "checkpoint_sha256": digest,
        "weight_prefix": prefix,
        "parameters": sum(p.numel() for p in model.parameters()),
        "mid_channels": 64,
        "num_blocks": 15,
    }


def portable_deform_conv2d(input, offset, weight, bias=None, stride=(1, 1),
                           padding=(0, 0), dilation=(1, 1), mask=None):
    """Equivalent modulated deformable convolution using GridSample and Conv.

    Preserves weights, offset ordering, zero padding, groups and masks.
    This is an export compatibility rewrite, NOT a smaller/faster model.
    """
    n, channels, height, width = input.shape
    out_channels, weight_channels, kh, kw = weight.shape
    oh, ow = offset.shape[-2:]
    k = kh * kw
    offset_groups = offset.shape[1] // (2 * k)
    groups = channels // weight_channels
    cg = channels // offset_groups
    sh, sw = stride if isinstance(stride, (tuple, list)) else (stride, stride)
    ph, pw = padding if isinstance(padding, (tuple, list)) else (padding, padding)
    dh, dw = dilation if isinstance(dilation, (tuple, list)) else (dilation, dilation)
    dtype, device = input.dtype, input.device
    yy = torch.arange(oh, dtype=dtype, device=device).view(1, 1, oh, 1, 1) * sh - ph
    xx = torch.arange(ow, dtype=dtype, device=device).view(1, 1, 1, ow, 1) * sw - pw
    ky = torch.arange(kh, dtype=dtype, device=device).repeat_interleave(kw).view(1, 1, 1, 1, k) * dh
    kx = torch.arange(kw, dtype=dtype, device=device).repeat(kh).view(1, 1, 1, 1, k) * dw
    offsets = offset.reshape(n, offset_groups, k, 2, oh, ow)
    y = yy + ky + offsets[:, :, :, 0].permute(0, 1, 3, 4, 2)
    x = xx + kx + offsets[:, :, :, 1].permute(0, 1, 3, 4, 2)
    grid = torch.stack((x * (2.0 / (width - 1)) - 1.0,
                        y * (2.0 / (height - 1)) - 1.0), dim=-1)
    sampled = F.grid_sample(input.reshape(n * offset_groups, cg, height, width),
                            grid.reshape(n * offset_groups, oh, ow * k, 2),
                            mode="bilinear", padding_mode="zeros", align_corners=True)
    sampled = sampled.reshape(n, offset_groups, cg, oh, ow, k).permute(0, 1, 2, 5, 3, 4)
    if mask is not None:
        sampled = sampled * mask.reshape(n, offset_groups, 1, k, oh, ow)
    sampled = sampled.reshape(n, channels * k, oh, ow)
    return F.conv2d(sampled, weight.reshape(out_channels, weight_channels * k, 1, 1), bias, groups=groups)


def enable_portable_deform():
    import torchvision
    torchvision.ops.deform_conv2d = portable_deform_conv2d


def register_onnx_deform_symbolic(opset=19):
    from torch.onnx import symbolic_helper, register_custom_op_symbolic

    @symbolic_helper.parse_args("v", "v", "v", "v", "v", "i", "i", "i", "i", "i", "i", "i", "i", "b")
    def symbolic(g, input, weight, offset, mask, bias, sh, sw, ph, pw, dh, dw, groups, offset_groups, use_mask):
        args = [input, weight, offset, bias]
        if use_mask:
            args.append(mask)
        return g.op("DeformConv", *args, strides_i=[sh, sw], pads_i=[ph, pw, ph, pw],
                    dilations_i=[dh, dw], group_i=groups, offset_group_i=offset_groups)
    register_custom_op_symbolic("torchvision::deform_conv2d", symbolic, opset)
