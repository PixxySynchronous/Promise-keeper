"""Distils Gnani Evon's answers into the small classifier the phone runs.

The app's on-device model is a multilingual sentence model (model_qint8_arm64.onnx, already 8-bit quantized)
with a logistic-regression "head" on top: 769 numbers in commitment_head.json. The original head was trained
on hand labels. This script trains the same head from the TEACHER's labels instead: Evon v3.3
(gnani/gnani-evon-v3.3-30B-A3B), run once on Kaggle (kaggle/evon_teacher.ipynb -> labels.jsonl).

It learns from Evon's confidence, not just its yes/no (a 0.6 "yes" pulls less than a 0.99 "yes"); that is
the distillation part. Pooling is identical to tools/train_semantic_head.py and CommitmentHead.kt, so the
Kotlin code needs no change: only the JSON file is replaced.

    python3 -m venv .venv && .venv/bin/pip install -r evon-app/training/requirements.txt     # once
    .venv/bin/python -I evon-app/training/train_evon_head.py labels.jsonl

Needs the real model file (118 MB, stored with Git LFS: `git lfs pull`), not the small pointer file.
Writes, inside evon-app/keeper/ only: the head, and the two test fixtures the Kotlin unit tests read.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from tokenizers import Tokenizer

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_pool import example_sentences  # noqa: E402

MAX_FALSE_ALARM_RATE = 0.05  # same budget as the original head: at most 1 in 20 ordinary messages may be flagged
TEACHER = "gnani/gnani-evon-v3.3-30B-A3B"
root = Path(__file__).resolve().parents[2]

parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
parser.add_argument("labels", nargs="*", help="labels.jsonl from the Kaggle notebook (and labels-unusable.jsonl, if any)")
parser.add_argument("--hand-labels", action="store_true",
                    help="no Evon yet: use the app's hand labels as a stand-in teacher, to try the pipeline (implies --dry-run)")
parser.add_argument("--false-alarms", action="append", default=[],
                    help="a false-alarms export from the app (False alarms tab -> Export); may be repeated")
parser.add_argument("--module", default=str(root / "evon-app/keeper"), help="the Android module to write into")
parser.add_argument("--dry-run", action="store_true", help="train and report, write nothing")
args = parser.parse_args()
if bool(args.labels) == args.hand_labels:
    raise SystemExit("give labels.jsonl, or --hand-labels to try the pipeline without Evon")
module = Path(args.module)
assets = module / "src/main/assets/semantic"
model_file = assets / "model_qint8_arm64.onnx"
if model_file.stat().st_size < 1_000_000:   # a Git LFS pointer, not the model: the original app has the same file
    model_file = root / "app/src/main/assets/semantic/model_qint8_arm64.onnx"
if model_file.stat().st_size < 1_000_000:
    raise SystemExit("model_qint8_arm64.onnx is a Git LFS pointer, not the model. Run: git lfs install --local && git lfs pull")

# --- the teacher's answers ---------------------------------------------------------------------------------
target = {}   # text -> probability of "promise"
if args.hand_labels:
    target = {t: (0.95 if label else 0.05) for t, label in example_sentences()}
    print("STAND-IN TEACHER: the app's hand labels, not Evon. This run only proves the pipeline.")
else:
    recovered = 0
    lines = [line for path in args.labels for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    for line in lines:
        row = json.loads(line)
        text = str(row.get("text", "")).strip()
        answer, conf = row.get("is_promise"), row.get("confidence")
        if not isinstance(answer, bool) and isinstance(row.get("raw"), str):
            # labels-unusable.jsonl: Evon answered, but under another key name ({"is_p": true}). The answer is
            # the one true/false in its reply; there is no token probability for these, so a flat 0.9.
            try:
                values = [v for v in json.loads(row["raw"]).values() if isinstance(v, bool)]
            except (ValueError, AttributeError):
                values = []
            if len(values) == 1:
                answer, conf, recovered = values[0], 0.9, recovered + 1
        if not text or text in target or not isinstance(answer, bool):
            continue
        conf = min(1.0, max(0.5, float(conf))) if isinstance(conf, (int, float)) else 1.0
        target[text] = conf if answer else 1.0 - conf   # according to Evon
    if recovered:
        print(f"{recovered} answers recovered from replies with a misspelt key (flat confidence 0.9)")
print(f"{len(target)} messages from the teacher")

# --- real false alarms: a person looked at each and said "not a promise", which outranks the teacher ---------
alarm_files = [Path(p) for p in args.false_alarms]
alarm_files += sorted((root / "evon-app/training/contributed").glob("*.json")) + sorted((root / "tools/contributed").glob("*.json"))
false_alarm_texts = set()
for path in alarm_files:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("format") != "promise-keeper-false-alarms":
        print(f"skipping {path.name}: not a Promise Keeper export")
        continue
    found = {m["text"].strip() for m in data.get("messages", []) if isinstance(m.get("text"), str) and m["text"].strip()}
    overruled = sum(target.get(t, 0.0) >= 0.5 for t in found)
    print(f"{path.name}: {len(found)} real false alarms" + (f" ({overruled} of them the teacher had called promises)" if overruled else ""))
    false_alarm_texts |= found
target.update({t: 0.0 for t in false_alarm_texts})

texts = list(target)
soft = np.array([target[t] for t in texts])
hard = (soft >= 0.5).astype(int)
print(f"training on {len(texts)} messages: {hard.sum()} promises, {len(hard) - hard.sum()} not")
if hard.sum() < 50 or (len(hard) - hard.sum()) < 50:
    raise SystemExit("too few labels to train on (need at least 50 of each kind)")

# --- how far does the teacher agree with people? -----------------------------------------------------------
hand = dict(example_sentences())
both = [i for i, t in enumerate(texts) if t in hand]
if both:
    agree = sum(bool(hard[i]) == hand[texts[i]] for i in both)
    print(f"Evon vs the app's hand labels on {len(both)} example sentences: agrees on {agree / len(both):.1%}")
    if agree / len(both) < 0.85:
        print("WARNING: the teacher disagrees with the hand labels a lot. Read some of its answers before shipping this head.")

# --- features: exactly what the phone computes --------------------------------------------------------------
tokenizer = Tokenizer.from_file(str(assets / "tokenizer.json"))
session = ort.InferenceSession(str(model_file), providers=["CPUExecutionProvider"])


def hidden_states(text):
    ids = np.array([tokenizer.encode(text.lower()).ids], dtype=np.int64)   # lowercased, as the app does
    return session.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids), "token_type_ids": np.zeros_like(ids)})[0][0]


def pool(hidden):
    tokens = hidden[1:-1] if len(hidden) > 2 else hidden
    mean, peak = tokens.mean(axis=0), tokens.max(axis=0)
    return np.concatenate([mean / np.linalg.norm(mean), peak / np.linalg.norm(peak)])


hidden = [hidden_states(t) for t in texts]
features = np.array([pool(h) for h in hidden], dtype=np.float32)


def fit(c, idx):
    """Logistic regression on soft targets: each message counts as a promise with weight p and as not one with 1 - p."""
    x = np.concatenate([features[idx], features[idx]])
    y = np.concatenate([np.ones(len(idx), dtype=int), np.zeros(len(idx), dtype=int)])
    w = np.concatenate([soft[idx], 1.0 - soft[idx]])
    keep = w > 1e-6
    return LogisticRegression(C=c, max_iter=5000).fit(x[keep], y[keep], sample_weight=w[keep])


# --- regularisation and threshold from 5-fold cross-validation (every score is on unseen messages) --------
folds = list(StratifiedKFold(n_splits=5, shuffle=True, random_state=0).split(features, hard))
best = None
for c in (4, 8, 16, 32, 64):
    probs = np.zeros(len(texts))
    for train_idx, test_idx in folds:
        probs[test_idx] = fit(c, train_idx).predict_proba(features[test_idx])[:, 1]
    for threshold in np.arange(0.30, 0.96, 0.01):
        predicted = probs >= threshold
        false_alarms, caught = predicted[hard == 0].mean(), predicted[hard == 1].mean()
        if false_alarms <= MAX_FALSE_ALARM_RATE and (best is None or caught > best[2]):
            best = (c, float(threshold), caught, false_alarms, probs)
if best is None:
    raise SystemExit("no setting keeps false alarms under the budget; the labels may be too noisy")
c, threshold, caught, false_alarms, probs = best
print(f"chosen C={c} threshold={threshold:.2f}")
print(f"cross-validated against Evon's answers: promises caught {caught:.0%}, ordinary messages flagged {false_alarms:.0%}")
if both:
    predicted = probs >= threshold
    pos = [i for i in both if hand[texts[i]]]
    neg = [i for i in both if not hand[texts[i]]]
    print(f"cross-validated against the HAND labels: promises caught {predicted[pos].mean():.0%}, "
          f"ordinary messages flagged {predicted[neg].mean():.0%}  (model alone, before the app's rules)")

if false_alarm_texts:
    idx = [i for i, t in enumerate(texts) if t in false_alarm_texts]
    still = int((probs[idx] >= threshold).sum())
    print(f"real false alarms: this head would still flag {still} of {len(idx)} when it has not seen them "
          f"(model alone; the rules reject some of these first)")

final = fit(c, np.arange(len(texts)))
head = {
    "description": "Logistic regression on [L2(mean), L2(max)] of model_qint8_arm64.onnx token vectors without <s>/</s>",
    "weights": [float(w) for w in final.coef_[0]],
    "bias": float(final.intercept_[0]),
    "threshold": round(threshold, 2),
    "teacher": TEACHER,
    "trained_on": len(texts),
    "cross_validated_recall": round(float(caught), 3),
    "cross_validated_false_alarm_rate": round(float(false_alarms), 3),
}
if args.dry_run or args.hand_labels:
    raise SystemExit("dry run: nothing written")
(assets / "commitment_head.json").write_text(json.dumps(head, indent=1), encoding="utf-8")

# Fixture for CommitmentHeadTest: token vectors with the probabilities scikit-learn gives them.
resources = module / "src/test/resources"
resources.mkdir(parents=True, exist_ok=True)
sample = [0, 1, 2, len(texts) - 1, len(texts) - 2, len(texts) // 2]
(resources / "commitment_head_reference.json").write_text(json.dumps([
    {"text": texts[i], "hidden": hidden[i].tolist(), "probability": float(final.predict_proba(features[i:i + 1])[0, 1])}
    for i in sample], ensure_ascii=False), encoding="utf-8")

# For PipelineAccuracyTest: the hand-labelled example sentences with this head's unseen probability, so the Kotlin
# rules and the distilled model are scored together against what people said, not against the teacher.
groups = {}
spec_text = (root / "tools/commitment_examples.py").read_text(encoding="utf-8")
import ast  # noqa: E402

for node in ast.parse(spec_text).body:
    name = getattr(node.targets[0], "id", "") if isinstance(node, ast.Assign) else ""
    if name in ("COMMITMENTS", "NON_COMMITMENTS"):
        for group, sentences in ast.literal_eval(node.value).items():
            groups.update({s: group for s in sentences})
cases = [{"text": texts[i], "label": int(hand[texts[i]]), "group": groups.get(texts[i], "other"), "probability": round(float(probs[i]), 5)}
         for i in both]
if cases:
    (resources / "commitment_eval.json").write_text(json.dumps({"threshold": round(threshold, 2), "cases": cases}, ensure_ascii=False), encoding="utf-8")
print(f"wrote {assets / 'commitment_head.json'} and the test fixtures in {resources}")
