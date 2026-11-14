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
   people's promises are rejected. This copy adds seven rejection rules to the original's (see below).
2. **The light model**, asked only when the rules found nothing and did not reject: a multilingual sentence
   model (118 MB, 8-bit quantized ONNX) with a tiny classifier on top, `commitment_head.json` (769 numbers).
3. A yes from either step raises the "keep this promise?" notification.

## Where Evon comes in: teacher and student

Evon has 30 billion parameters (63.5 GB of weights), so it cannot run on a phone. A 4-bit copy (18.4 GB) runs
once as the **teacher**, on your own computer if it has about 24 GB of memory, or on a free Kaggle GPU:

| Step | Where | Command / file |
|---|---|---|
| 1. Collect messages to label | your machine | `python3 evon-app/training/make_pool.py --chat my-chat.txt --me "My Name"` |
| 2. Evon labels each one (promise or not, with a confidence) | your machine | `llama-server` + `python3 evon-app/training/label_local.py` → `results/labels-evon.jsonl` (the commands are at the top of that file) |
| 3. Distil into the phone's classifier | your machine | `evon-app/training/.venv/bin/python -I evon-app/training/train_evon_head.py results/labels-evon.jsonl` |
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

Six rules in `CommitmentDetector.kt` also come from real false alarms (a seventh, refusals such as "I will
not" and "kabhi nahi", came from testing): a request anywhere in the message ("buddy can you send it by
tonight") unless the sender also promises, a how/why question about one's own future ("kaise karunga"),
"thori/thodi" right before the verb ("wo thori bataunga"), a quoted future ("kal karunga mt bolio"),
"I will suggest", and a question tag ("... hota haina"). `FalseAlarmRulesTest.kt` covers them. On the app's
example messages they lose no promise.

### How Evon is asked

Asking "is this a promise, true or false?" did not work: with its thinking switched off, the 4-bit Evon agreed
with people on only 73-78% of the example messages, and its own thinking mode took 20-30 seconds a message.
Two narrow questions work, at about a second a message:

1. Who would act (sender, other, nobody), when (future, past, none), and is the sender sure (yes, no)?
2. For sender + future + yes only: what does the message do (commit, request, plan, maybe, state, question)?

A promise is sender + future + yes + commit. The confidence is the probability Evon's own next-token
distribution gave those four words, multiplied. With this, Evon agrees with the hand labels on 95.1% of the
976 messages. The questions' wording was tuned on those same messages, so that figure is not an independent
score of Evon.

The Kaggle notebook (`kaggle/evon_teacher.ipynb`) builds and runs the same model on two T4 GPUs, but it still
asks the older single question; its labels from 14 Nov are kept in `results/labels.jsonl` for the record and
are not used.

To try the pipeline before Evon's labels exist: `train_evon_head.py --hand-labels` (writes nothing).

## Results (15 Nov 2026, version 0.2.0-evon)

Scored by `PipelineAccuracyTest` on the 976 example messages, with model probabilities from classifiers that
never saw the message being scored:

| | Promises caught | Other messages flagged |
|---|---|---|
| Rules only | 87.4% | 0.0% |
| Evon-taught model only | 58.9% | 3.1% |
| **Rules + Evon-taught model (this app)** | **94.0%** | **0.4%** |
| Rules + the original hand-labelled model | 95.1% | 0.8% |

So the Evon-taught model halves the false alarms and catches slightly fewer promises. Of the 52 real false
alarms, the new model alone would still flag 7 if it had not seen them; the rules reject some of those first.

- APK: 124 MB (debug build, 64-bit ARM only). The original's release APK is about 130 MB. Almost all of it is
  the sentence model.
- 69 unit tests pass (`./gradlew :keeper:testDebugUnitTest`).
- Privacy: no `INTERNET` permission, no network library, no new data read. Evon is used only on the
  developer's machine to make training labels; nothing of it is in the app.

## State

- Not yet tried on a phone.
- The quantized Evon files are in a community repository (`luminenio/gnani-evon-v3.3-30B-A3B-GGUF`) whose card
  says downloads need the maintainer's approval.
- The in-app links (source, releases, false-alarm form) still point to the original app's repository.
- Use only your own chats or made-up messages for the pool.
