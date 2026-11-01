# /// script
# requires-python = ">=3.11,<3.14"
# dependencies = ["flatbuffers==25.2.10", "numpy==2.2.6"]
# ///
"""Extract the quantized pooled features from the pinned AI Hub MobileNetV2."""

import argparse
import hashlib
import importlib.util
import json
import subprocess
import tempfile
from pathlib import Path

import flatbuffers
import numpy as np

SOURCE_SHA256 = "9b7c4606b5ee40b7e1a7426df439f5d5a8a631741f811dea7db02927e3b3704d"
SCHEMA_SHA256 = "db0f2166cdd9546387b497ed43f743b7c5b6951ed5bb099101f85170c0cd13f8"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def export(source: Path, output: Path, flatc: str) -> None:
    original = source.read_bytes()
    if sha256(original) != SOURCE_SHA256:
        raise ValueError("Source differs from the pinned AI Hub classification model")
    schema = Path(__file__).parent / "vendor" / "tflite_schema.fbs"
    if sha256(schema.read_bytes()) != SCHEMA_SHA256:
        raise ValueError("TFLite schema checksum mismatch")
    version = subprocess.check_output([flatc, "--version"], text=True).strip()
    if version != "flatc version 25.2.10":
        raise ValueError(f"Expected flatc 25.2.10, found {version}")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix="tongxing-feature-export-", dir=output.parent
    ) as directory:
        subprocess.run(
            [
                flatc,
                "--python",
                "--gen-object-api",
                "--gen-onefile",
                "-o",
                directory,
                str(schema),
            ],
            check=True,
        )
        spec = importlib.util.spec_from_file_location(
            "tflite_schema_generated", Path(directory) / "tflite_schema_generated.py"
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        model = module.ModelT.InitFromObj(module.Model.GetRootAsModel(original))
        if len(model.subgraphs) != 1:
            raise ValueError("Expected one MobileNetV2 subgraph")
        graph = model.subgraphs[0]
        classifiers = [
            index
            for index, operator in enumerate(graph.operators)
            if max(
                model.operatorCodes[operator.opcodeIndex].builtinCode,
                model.operatorCodes[operator.opcodeIndex].deprecatedBuiltinCode,
            )
            == module.BuiltinOperator.FULLY_CONNECTED
        ]
        if len(classifiers) != 1:
            raise ValueError("Expected one final classification layer")
        cut = classifiers[0]
        feature_index = int(graph.operators[cut].inputs[0])
        feature = graph.tensors[feature_index]
        if list(feature.shape) != [1, 1280] or feature.type != module.TensorType.INT8:
            raise ValueError(
                "Expected a quantized 1280-dimensional pooled feature tensor"
            )
        if list(graph.tensors[int(graph.inputs[0])].shape) != [1, 224, 224, 3]:
            raise ValueError("Unexpected image input layout")

        # The retained tensor keeps its original scale and zero point. The Android
        # runner dequantizes this output and applies L2 normalization.
        graph.operators = graph.operators[:cut]
        graph.outputs = np.array([feature_index], dtype=np.int32)
        used = set(map(int, graph.inputs)) | {feature_index}
        for operator in graph.operators:
            for attribute in ("inputs", "outputs", "intermediates"):
                values = getattr(operator, attribute)
                if values is not None:
                    used.update(int(value) for value in values if value >= 0)
        tensor_map = {old: new for new, old in enumerate(sorted(used))}
        graph.tensors = [graph.tensors[old] for old in sorted(used)]

        def remap(values):
            return np.array(
                [tensor_map[int(value)] if value >= 0 else -1 for value in values],
                dtype=np.int32,
            )

        graph.inputs = remap(graph.inputs)
        graph.outputs = remap(graph.outputs)
        for operator in graph.operators:
            for attribute in ("inputs", "outputs", "intermediates"):
                values = getattr(operator, attribute)
                if values is not None:
                    setattr(operator, attribute, remap(values))

        model.signatureDefs = []
        model.metadata = []
        model.metadataBuffer = None
        used_buffers = sorted({0} | {tensor.buffer for tensor in graph.tensors})
        buffer_map = {old: new for new, old in enumerate(used_buffers)}
        model.buffers = [model.buffers[old] for old in used_buffers]
        for tensor in graph.tensors:
            tensor.buffer = buffer_map[tensor.buffer]
        model.description = (
            b"TongXing MobileNetV2 pooled features from quantized AI Hub classifier"
        )
        builder = flatbuffers.Builder(0)
        builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
        result = bytes(builder.Output())

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(result)
    record = {
        "source_sha256": SOURCE_SHA256,
        "schema_sha256": SCHEMA_SHA256,
        "flatc": version,
        "output_sha256": sha256(result),
        "output_bytes": len(result),
        "retained_operators": cut,
        "input_shape": [1, 224, 224, 3],
        "output_shape": [1, 1280],
        "output_dtype": "INT8",
        "normalization": "L2 in Android FeatureExtractorRunner",
    }
    output.with_suffix(".provenance.json").write_text(
        json.dumps(record, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--flatc", default="flatc")
    arguments = parser.parse_args()
    export(arguments.source, arguments.output, arguments.flatc)
