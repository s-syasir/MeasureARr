#!/usr/bin/env python3
"""
Exports Depth Anything V2 Metric Indoor Small to ONNX and places the file
in android/app/src/main/assets/ so it gets bundled into the APK.

Run once from the repo root:
    pip install torch transformers
    python tools/export_model.py

The model (depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf) is
fine-tuned on the Hypersim indoor dataset and outputs absolute depth in
METERS for each pixel, capped at 20 m. Input: 518×518 RGB, ImageNet-normalised.
"""
import sys
from pathlib import Path

OUTPUT = Path(__file__).resolve().parent.parent / \
    "android/app/src/main/assets/depth_metric_indoor.onnx"


def main():
    try:
        import torch
        from transformers import DepthAnythingForDepthEstimation
    except ImportError:
        sys.exit("Run: pip install torch transformers")

    MODEL_ID = "depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf"
    print(f"Downloading {MODEL_ID} from Hugging Face …")
    model = DepthAnythingForDepthEstimation.from_pretrained(MODEL_ID)
    model.eval()

    # Wrap so ONNX export sees a plain Tensor in → Tensor out graph.
    class _Wrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
            return self.m(pixel_values=pixel_values).predicted_depth

    wrapper = _Wrapper(model)
    dummy = torch.zeros(1, 3, 518, 518)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    print(f"Exporting ONNX → {OUTPUT} …")
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy,),
            str(OUTPUT),
            input_names=["pixel_values"],
            output_names=["predicted_depth"],
            opset_version=17,
            do_constant_folding=True,
        )

    mb = OUTPUT.stat().st_size / 1_000_000
    print(f"Done — {mb:.0f} MB written.")
    print("Now rebuild the APK: ./android/gradlew assembleDebug")


if __name__ == "__main__":
    main()
