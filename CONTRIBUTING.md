# Contributing to Promise Keeper

Thanks for wanting to help. This page lists the areas where help is needed most and the rules every pull request has to follow.

## The one rule: everything stays on the phone

Promise Keeper reads the messages people send in WhatsApp. Privacy is its first priority, and nothing is merged if it weakens that. If an improvement can't be done fully on the device, it doesn't go in. Every pull request must keep these:

- **No internet access.** The app must not request the `INTERNET` permission, or any other permission that lets data leave the phone. That includes indirect routes: no libraries with networking code, analytics, crash reporting, ads or cloud APIs, even ones that "only send anonymous data".
- **All processing on the device.** Detection, models and parsing run locally. No server calls, no online model hosting, no download-on-first-launch.
- **No new data collected.** Don't read more than the messages the user sends and the chat name. Don't watch other apps or received messages.
- **Data stays private.** Reminders stay in the app's private storage, excluded from backups.

If you're not sure whether a change fits these rules, open an issue and ask before you start.

## Where help is needed

### 1. Make the app smaller

The APK is about 130 MB. Almost all of that is the language model: `app/src/main/assets/semantic/model_qint8_arm64.onnx` (about 118 MB, a quantised multilingual MiniLM) and its `tokenizer.json` (about 9 MB).

A smaller model, better compression, a trimmed vocabulary or any other way to cut the size is very welcome, as long as:

- it still runs fully on the device, with no download after install
- it still handles English, Hinglish and Hindi (Devanagari)
- accuracy doesn't drop noticeably. Run `./gradlew testDebugUnitTest`, which includes the accuracy checks, and put the before and after numbers in your pull request.

### 2. Better understanding of what a message means

Right now detection relies mostly on keywords. A phrase list catches most promises ([CommitmentPhrases.kt](app/src/main/java/com/satyam/whatsappbot/domain/CommitmentPhrases.kt), with rules in [CommitmentDetector.kt](app/src/main/java/com/satyam/whatsappbot/domain/CommitmentDetector.kt)). When no phrase matches, the language model is used, but only with a simple linear classifier on top (`commitment_head.json`). So the app doesn't really understand meaning: it misses promises worded in unusual ways and flags some messages that aren't promises.

This is limited by the same constraint as above: the model must run locally on an ordinary phone and stay small. Ideas that improve real understanding within those limits are welcome, such as a better classifier, a small model fine-tuned for this task, or smarter rules. Include accuracy numbers from the test suite or `tools/train_semantic_head.py`. See [How detection works](README.md#how-detection-works) and [Retraining](README.md#retraining) in the README.

Adding labelled example messages to [tools/commitment_examples.py](tools/commitment_examples.py) also helps, especially Hinglish and Hindi ones. Users can also share false alarms they export from the app; see [tools/contributed/](tools/contributed/README.md).

### 3. An app icon

The app has no icon yet and uses the Android default. A simple, clear icon that fits the idea of keeping promises and reminders would be great. Please provide:

- an adaptive icon (separate foreground and background layers), plus a monochrome layer for themed icons
- source files (SVG or a vector drawable) so it can be edited later
- artwork you made yourself or that has a licence allowing use in this project. No WhatsApp or Meta branding.

### 4. A better UI

The interface works but is plain. The screens are built with Jetpack Compose in [MainActivity.kt](app/src/main/java/com/satyam/whatsappbot/MainActivity.kt) (reminders, false alarms), [SetupScreen.kt](app/src/main/java/com/satyam/whatsappbot/SetupScreen.kt) (setup and settings), [AlarmActivity.kt](app/src/main/java/com/satyam/whatsappbot/AlarmActivity.kt) and the notifications in [ReminderNotifications.kt](app/src/main/java/com/satyam/whatsappbot/ReminderNotifications.kt). Improvements to layout, visual design, dark mode, accessibility (screen readers, font scaling) and clarity are all welcome. Include before and after screenshots in your pull request.

## How to submit a change

1. For anything bigger than a small fix, open an issue first to describe what you plan to do.
2. Fork the repository and set it up as described in [Building from source](README.md#building-from-source). The model is stored with Git LFS, so install that before cloning.
3. Make your change on a branch. Keep the pull request focused on one thing.
4. Run `./gradlew testDebugUnitTest` and make sure it passes.
5. Open a pull request that explains:
   - what it changes and why
   - accuracy and APK size before and after, if it touches detection or the model
   - screenshots, if it changes the UI
   - confirmation that it adds no internet access, network libraries or new data collection

Pull requests that break the privacy rule above will be closed, however useful the rest of the change is.
