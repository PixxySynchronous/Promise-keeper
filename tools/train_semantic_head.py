"""Trains the small classifier that sits on top of the bundled sentence model, entirely on this machine.

Each labelled message in tools/commitment_examples.py is run through the same ONNX model and tokenizer the app
ships. Its token vectors (excluding <s> and </s>) are pooled two ways, mean and per-dimension max, each L2-normalised
and concatenated into 768 features; a logistic regression is fitted on those. The result is 769 numbers plus a
threshold, written to app/src/main/assets/semantic/commitment_head.json. CommitmentHead.kt must pool the same way.

Usage: .venv/bin/python -I tools/train_semantic_head.py
"""
import importlib.util
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from tokenizers import Tokenizer

MAX_FALSE_ALARM_RATE = 0.05  # at most 1 in 20 ordinary messages may trigger a suggestion

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/main/assets/semantic"
spec = importlib.util.spec_from_file_location("commitment_examples", root / "tools/commitment_examples.py")
examples = importlib.util.module_from_spec(spec)
spec.loader.exec_module(examples)

rows = [(text, 1, group) for group, texts in examples.COMMITMENTS.items() for text in texts]
rows += [(text, 0, group) for group, texts in examples.NON_COMMITMENTS.items() for text in texts]
seen, unique = set(), []
for row in rows:
    if row[0] in seen:
        print(f"skipping duplicate: {row[0]!r}")
        continue
    seen.add(row[0])
    unique.append(row)
texts = [r[0] for r in unique]
labels = np.array([r[1] for r in unique])
groups = [r[2] for r in unique]
print(f"{len(texts)} messages: {labels.sum()} commitments, {len(labels) - labels.sum()} non-commitments")

tokenizer = Tokenizer.from_file(str(assets / "tokenizer.json"))
session = ort.InferenceSession(str(assets / "model_qint8_arm64.onnx"), providers=["CPUExecutionProvider"])


def hidden_states(text):
    ids = np.array([tokenizer.encode(text).ids], dtype=np.int64)
    return session.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids), "token_type_ids": np.zeros_like(ids)})[0][0]


def pool(hidden):
    tokens = hidden[1:-1] if len(hidden) > 2 else hidden
    mean, peak = tokens.mean(axis=0), tokens.max(axis=0)
    return np.concatenate([mean / np.linalg.norm(mean), peak / np.linalg.norm(peak)])


hidden = [hidden_states(t) for t in texts]
features = np.array([pool(h) for h in hidden], dtype=np.float32)

# Pick regularisation and threshold from 5-fold cross-validation, so every score below is on unseen messages.
folds = StratifiedKFold(n_splits=5, shuffle=True, random_state=0)
best = None
for c in (4, 8, 16, 32, 64):
    probs = cross_val_predict(LogisticRegression(C=c, max_iter=5000, class_weight="balanced"), features, labels, cv=folds, method="predict_proba")[:, 1]
    for threshold in np.arange(0.30, 0.96, 0.01):
        predicted = probs >= threshold
        false_alarms = predicted[labels == 0].mean()
        caught = predicted[labels == 1].mean()
        if false_alarms <= MAX_FALSE_ALARM_RATE and (best is None or caught > best[2]):
            best = (c, float(threshold), caught, false_alarms, probs)
c, threshold, caught, false_alarms, probs = best
print(f"\nchosen C={c} threshold={threshold:.2f}")
print(f"cross-validated: promises caught {caught:.0%}, ordinary messages wrongly flagged {false_alarms:.0%}")

predicted = probs >= threshold
print("\nper group (unseen messages):")
for group in dict.fromkeys(groups):
    idx = [i for i, g in enumerate(groups) if g == group]
    for label, name in ((1, "commitments caught"), (0, "wrongly flagged")):
        sel = [i for i in idx if labels[i] == label]
        if sel:
            print(f"  {group:<24} {name:<19} {predicted[sel].sum():>3}/{len(sel)}")
print("\nmissed promises:", [texts[i] for i in range(len(texts)) if labels[i] == 1 and not predicted[i]][:25])
print("false alarms:", [texts[i] for i in range(len(texts)) if labels[i] == 0 and predicted[i]][:25])

final = LogisticRegression(C=c, max_iter=5000, class_weight="balanced").fit(features, labels)
head = {
    "description": "Logistic regression on [L2(mean), L2(max)] of model_qint8_arm64.onnx token vectors without <s>/</s>",
    "weights": [float(w) for w in final.coef_[0]],
    "bias": float(final.intercept_[0]),
    "threshold": round(threshold, 2),
    "trained_on": len(texts),
    "cross_validated_recall": round(float(caught), 3),
    "cross_validated_false_alarm_rate": round(float(false_alarms), 3),
}
(assets / "commitment_head.json").write_text(json.dumps(head, indent=1), encoding="utf-8")

# Fixture for CommitmentHeadTest: raw model output (token vectors) with the probabilities scikit-learn gives them.
sample = [0, 1, 2, len(texts) - 1, len(texts) - 2, len(texts) // 2]
fixture = [
    {"text": texts[i], "hidden": hidden[i].tolist(), "probability": float(final.predict_proba(features[i:i + 1])[0, 1])}
    for i in sample
]
test_resources = root / "app/src/test/resources"
test_resources.mkdir(parents=True, exist_ok=True)
(test_resources / "commitment_head_reference.json").write_text(json.dumps(fixture, ensure_ascii=False), encoding="utf-8")

# For PipelineAccuracyTest: every message with its cross-validated (unseen) model probability, so the app's real
# Kotlin rules can be scored together with the model without re-implementing them here.
evaluation = {
    "threshold": round(threshold, 2),
    "cases": [{"text": t, "label": int(l), "group": g, "probability": round(float(p), 5)} for t, l, g, p in zip(texts, labels, groups, probs)],
}
(test_resources / "commitment_eval.json").write_text(json.dumps(evaluation, ensure_ascii=False), encoding="utf-8")
print(f"\nwrote {assets / 'commitment_head.json'}")
