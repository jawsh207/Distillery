#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Makes the stand-in files that let Moonshine's speech engine start without
its own English pronunciation data.

Moonshine's synthesizer refuses to load unless it finds an English dictionary
and an "out of vocabulary" model under en_us/, even though Distillery never
asks it to pronounce text (it hands Kokoro phonemes directly). These stand-ins
satisfy that check and contain nothing of Moonshine's:

  dict.tsv          one placeholder entry
  onnx-config.json  the tables the loader validates
  model.ort         a one-node Identity model in ONNX Runtime format

    pip install onnx==1.17.0 onnxruntime==1.23.2
    python3 tools/make-g2p-standin.py
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import onnx
from onnx import TensorProto, helper

out = Path(__file__).resolve().parent.parent / "app/src/debug/assets/tts/standin"
out.mkdir(parents=True, exist_ok=True)

(out / "dict.tsv").write_text("distillery\tdɪstˈɪləɹi\n", encoding="utf-8")

config = {
    "config_schema_version": 1,
    "model_kind": "oov",
    "char_vocab": {"<pad>": 0, "<unk>": 1},
    "phoneme_vocab": {"<pad>": 0, "<bos>": 1, "<eos>": 2},
    "train_config": {"max_seq_len": 1},
    "oov_index": {"max_phoneme_len": 1},
    "note": "Distillery stand-in. Never run: Distillery pronounces words itself.",
}
(out / "onnx-config.json").write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")

graph = helper.make_graph(
    [helper.make_node("Identity", ["x"], ["logits"])],
    "distillery_standin",
    [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1])],
    [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1])],
)
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], producer_name="distillery")
model.ir_version = 8
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / "model.onnx"
    onnx.save(model, src)
    subprocess.run(
        [sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", str(src), "--output_dir", tmp],
        check=True,
    )
    (out / "model.ort").write_bytes((Path(tmp) / "model.ort").read_bytes())
for f in sorted(out.iterdir()):
    print(f.name, f.stat().st_size)
