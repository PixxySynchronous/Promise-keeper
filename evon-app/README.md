# Promise Keeper Evon

A copy of the Android app in [`app/`](../app) (by Piyush S): it watches the messages you send in WhatsApp, spots
a promise, and shows a notification asking whether to keep it as a reminder. Everything runs on the phone and
the app has no internet permission.

What is different here: the small on-device model is taught by **Gnani Evon v3.3**
(`gnani/gnani-evon-v3.3-30B-A3B`) instead of by hand labels. It has its own application id
(`com.promisekeeper.evon`), so it installs next to the original app.

## How a message is judged

1. **Rules** (`CommitmentDetector.kt`): promise phrases plus tense checks. A first-person future
   ("bhej dunga", "I'll send") is a promise; questions, requests, past tense, habits, refusals and other
   people's promises are rejected. One rule was added to the original's: "I will not", "not going to" and
   "kabhi nahi" are refusals.
2. **The light model**, asked only when the rules found nothing and did not reject: a multilingual sentence
   model (118 MB, 8-bit quantized ONNX) with a tiny classifier on top, `commitment_head.json` (769 numbers).
3. A yes from either step raises the "keep this promise?" notification.

## Where Evon comes in: teacher and student

Evon has 30 billion parameters (63.5 GB of weights), so it cannot run on a phone. It runs once, on a free
Kaggle GPU, as the **teacher**:

| Step | Where | Command / file |
|---|---|---|
| 1. Collect messages to label | your machine | `python3 evon-app/training/make_pool.py --chat my-chat.txt --me "My Name"` |
| 2. Evon labels each one (promise or not, with a confidence) | Kaggle, GPU T4 x2 | `kaggle/evon_teacher.ipynb` (runs a 4-bit quantized Evon) → `labels.jsonl` |
| 3. Distil into the phone's classifier | your machine | `.venv/bin/python -I evon-app/training/train_evon_head.py labels.jsonl` |
| 4. Build the app | your machine | `./gradlew :keeper:assembleDebug` from `evon-app/` (JDK 17 and the Android SDK) |

Step 3 trains the **student** from Evon's confidence, not just its yes/no, and writes a new
`keeper/src/main/assets/semantic/commitment_head.json`. The file has the same shape as the original, so no
Kotlin code changes with it. The script also prints how often Evon agrees with the hand labels, and how the
new head scores against them, so a bad teacher run is visible before it ships. It needs the real model file
(`git lfs pull`) and the packages in `training/requirements.txt`.

### Real false alarms

A false-alarms export from the app (False alarms tab → Export) is used in step 3 as well:

    .venv/bin/python -I evon-app/training/train_evon_head.py labels.jsonl --false-alarms my-export.json

A person looked at each of those messages and said "not a promise", so they outrank the teacher's answer.
Exports placed in `training/contributed/` (or the original app's `tools/contributed/`) are picked up without
the flag. The script reports how many of them the new head would still flag.

Four rules in `CommitmentDetector.kt` also come from real false alarms: a request anywhere in the message
("buddy can you send it by tonight") unless the sender also promises, a how/why question about one's own
future ("kaise karunga"), "thori/thodi" right before the verb ("wo thori bataunga"), and a quoted future
("kal karunga mt bolio"). On the app's 981-message eval set they lose no promise.

To try the pipeline before Evon's labels exist: `train_evon_head.py --hand-labels` (writes nothing).

## State (14 Nov 2026)

- Until step 3 has been run, `commitment_head.json` here is still the original hand-labelled head.
- Not yet run: the notebook, the training script, and a build of this project. `make_pool.py` has been run.
- The quantized Evon files are in a community repository (`luminenio/gnani-evon-v3.3-30B-A3B-GGUF`) whose card
  says downloads need the maintainer's approval. The notebook's last cell describes the fallback.
- The in-app links (source, releases, false-alarm form) still point to the original app's repository.
- Use only your own chats or made-up messages for the pool.
