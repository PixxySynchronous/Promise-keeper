# Promise Keeper

Promise Keeper is an Android app that notices when you promise something in WhatsApp and offers to remind you.

Send *"kal bhej dunga"*, *"I'll call you at 5"* or *"मैं कल भेज दूंगी"*, and a notification asks whether to set a reminder. It suggests a time from what you wrote ("tomorrow", "by EOD", "in 10 mins", "5 baje", "1 o clock", "8.30pm", "28 May"). You can keep it, change the time or ignore it.

It understands **English, Hinglish and Hindi (Devanagari)**.

**Notification or alarm:** each reminder can go off as a normal notification or as an alarm that rings until you stop it, even on a locked phone. Choose the default under **⚙ Settings → Remind me with**, or switch any reminder on its card.

**Set a reminder on purpose:** start any message with the word **reminder**, for example *"Reminder: pay rent on 1/11"* or *"reminder call mom at 5"*. You always get a reminder for it, with no detection involved. Send it to yourself (WhatsApp → *Message yourself*) to keep it private.

**Repeating reminders:** say how often and the reminder repeats, for example *"I'll call mom every Sunday"*, *"har somvar mandir jaunga"*, *"roz subah 7 baje walk karunga"*, *"Reminder: pay rent on the 1st of every month"* or *"reminder every year on 25/12 wish him"*. It understands every day, every morning or night, given weekdays, weekdays and weekends, every week, every month and every year. Each repeating reminder has an on/off switch on its card. **Done** only dismisses that one ring.

**Birthdays and bills repeat by themselves:** birthdays and anniversaries repeat every year (*"Piyush birthday 28 May"*), and rent, EMI, salary or bills with a day of the month repeat every month (*"pay rent on the 1st"*), without saying "every".

**Make a reminder in the app:** tap **New reminder** to make a reminder yourself, either once or repeating every day, every week (on the days you pick), every month or every year. **Edit** on any reminder changes its time, makes it repeat or renames it.

**See everything it can do:** the strip at the top of the home screen cycles through the features. Tap it for the full list, the installed version and what's new.

## Privacy

- **No internet access.** The app does not request Android's `INTERNET` permission, so Android itself blocks it from connecting to anything. Your messages cannot leave the phone.
- **On-device AI.** Detection runs entirely on the phone: a phrase list plus a small multilingual language model.
- **WhatsApp only.** The accessibility service is limited to WhatsApp and WhatsApp Business. It reads the messages you type and send, plus the chat name.
- **Stored locally.** Reminders are saved only in the app's private storage, which is excluded from backups. **Delete** removes the reminder and its message permanently.
- **Check it yourself.** Tap **Verify** under the app's title. Android's app info shows Promise Keeper has used 0 B of mobile data and Wi-Fi, and its permission list, [AndroidManifest.xml](app/src/main/AndroidManifest.xml), has no `INTERNET` line.

## Install

Requires **Android 13 or newer**.

**Easiest:** open **[pixxysynchronous.github.io/Promise-keeper](https://pixxysynchronous.github.io/Promise-keeper/)** on your phone. It has the download button and the install steps on one page. Share that link with friends.

Or install by hand:

1. Download `PromiseKeeper-1.4.0.apk` from the [Releases](../../releases) page.
2. Open it. If asked, allow your browser or file manager to install unknown apps.
3. **Google Play Protect may block or warn about the app.** Play Protect does this for apps downloaded outside the Play Store that use the accessibility service, which this app needs to see the messages you send.
   - If you see **Install anyway**, tap it.
   - If installation is **blocked** with a message like *"This app requests access to sensitive data"* and there is no Install anyway (common in India and some other countries):
     1. Open the **Play Store**, tap your profile picture, then **Play Protect**.
     2. Tap the **⚙️ gear** and turn off **Scan apps with Play Protect**.
     3. Install the APK.
     4. **Turn Play Protect back on.** If it later flags Promise Keeper, choose **Keep app**.
   - Alternatively, installing from a computer with `adb install PromiseKeeper-1.4.0.apk` is not blocked.
4. **If Promise Keeper is greyed out in Accessibility** ("Restricted setting"), Android has locked it because it came from a downloaded file. Unlock it once:
   1. In **Settings → Accessibility → Installed apps**, tap the greyed-out **Promise Keeper**, then tap **OK** on the popup. This first try is needed, or the option below doesn't appear.
   2. Go to **Settings → Apps → Promise Keeper → ⋮ (top right) → Allow restricted settings**, and confirm with your PIN or fingerprint.
   3. Go back to Accessibility and turn **Promise Keeper** on.

   **Can't find these options?** Every phone brand puts them in a slightly different place. Use the search bar at the top of your phone's **Settings** and type *restricted settings*, or search online for *allow restricted settings* with your phone's name. The steps are the same for any app installed from a downloaded file.
5. Open **Promise Keeper**. A setup guide walks you through:
   - allowing notifications
   - turning on the accessibility service
   - allowing exact alarms
   - removing battery limits
   
   If the accessibility switch is greyed out ("Restricted setting"), the guide explains how to allow it.
6. Test it: send yourself `kal bhej dunga` in WhatsApp. A *Reminder suggestion* notification should appear.

**Bank or UPI app says Promise Keeper is suspicious?** Some payment apps refuse to run while an accessibility service from a downloaded app is on. Before paying, open Promise Keeper → **⚙ Settings** → **Turn off for now**. Afterwards, turn it back on from step 2 of the same screen. Promises you send while it's off aren't noticed, and you get a reminder once a day to turn it back on.

The app is about 130 MB, mostly the language model.

## How well it works

Measured on 968 labelled example messages, scoring each with a model that had not been trained on that message:

| | Promises caught | Ordinary messages wrongly flagged |
|---|---|---|
| English | 87% | 1% |
| Hinglish | 98% | 2% |
| Hindi | 100% | 0% |
| Overall | 95% | 1% |

Detection uses phrase rules first and the AI model for what they miss. On their own, the rules catch 81% of promises and the model 70%; together, 95%.

These examples were written by hand, not taken from real chats, and the phrase rules were written using them, so real-world results will be lower. Very short or unusual messages are the most likely to be missed.

## Help improve detection

When a suggestion isn't really a promise, tap **Skip → Not a promise** on the notification or the reminder card. It moves to the **False alarms** tab. (If it *was* a promise you just don't need reminding about, choose **No reminder needed** instead, so it isn't counted as a false alarm.) In the tab you can:
- tap **It was a promise** to move it back
- delete it
- tap **Export** to share them and help train the model for everyone

**How to share them:** tap **Export → Copy & open GitHub form**. Your browser opens the [Share false alarms](https://github.com/PixxySynchronous/Promise-keeper/issues/new?template=false-alarms.yml) form. Long-press the box, tap **Paste**, then **Submit new issue**. You need a free GitHub account. Prefer a file? Tap **Share file** and attach it to the same form. Details are in [`tools/contributed/`](tools/contributed/README.md).

Only the message text and what flagged it are shared, never contact names. Check for anything private first, since what you post on GitHub is public. The app has no internet access: the copy stays on your phone until you paste it yourself.

## Known limitations

- **Contact names are guessed** from the WhatsApp screen and are sometimes wrong.
- **Some brands kill background apps aggressively**, including Xiaomi, Oppo, Vivo, Realme and Huawei. Follow the battery step in the setup guide.
- **Only messages you send are checked.** Messages you receive are not.

## Contributing

Help is welcome with app size, detection, an app icon and the UI. See [CONTRIBUTING.md](CONTRIBUTING.md). Everything must keep running on the phone: privacy comes first.

Like the app? A ⭐ star on this repository works like a rating and helps others find it.

## Building from source

The model file is stored with [Git LFS](https://git-lfs.com). Install it before cloning:

```sh
git lfs install
git clone <this repo>
./gradlew assembleDebug                      # debug build
./gradlew assembleRelease                    # needs a signing key, see below
./gradlew testDebugUnitTest                  # unit tests, including accuracy checks
```

Requirements: JDK 17 and the Android SDK (compileSdk 36).

**Release signing:** the build reads `~/.promise-keeper/keystore.properties`, or the path in `PROMISE_KEEPER_KEYSTORE`. The file sets `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without that file, the release build is unsigned.

### How detection works

1. **Phrase list:** [CommitmentPhrases.kt](app/src/main/java/com/satyam/whatsappbot/domain/CommitmentPhrases.kt) plus rules that reject questions, requests, things already done, other people's promises, maybes and refusals ([CommitmentDetector.kt](app/src/main/java/com/satyam/whatsappbot/domain/CommitmentDetector.kt)).
2. **Language model:** if no phrase matches, a multilingual sentence model (`assets/semantic/model_qint8_arm64.onnx`, run with ONNX Runtime) produces token vectors. A small logistic-regression classifier (`commitment_head.json`) then decides.
3. **Tokenizer:** a pure-Kotlin port of the Hugging Face tokenizer, [SentencePieceTokenizer.kt](app/src/main/java/com/satyam/whatsappbot/domain/SentencePieceTokenizer.kt). It replaces the original library, which contains networking code. A unit test checks it token-for-token against the original on 3,800+ inputs.

### Retraining

Add messages to [tools/commitment_examples.py](tools/commitment_examples.py), then:

```sh
python -m venv .venv && .venv/bin/pip install -r tools/requirements.txt
.venv/bin/python -I tools/train_semantic_head.py       # writes commitment_head.json, prints accuracy
.venv/bin/python -I tools/make_tokenizer_reference.py  # refreshes the tokenizer test fixture
```

## Credits

The bundled sentence model appears to be a quantised [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) from Sentence Transformers, licensed Apache-2.0. *(Maintainer: confirm the source and keep its license notice.)* The app uses [ONNX Runtime](https://onnxruntime.ai) (MIT).

WhatsApp is a trademark of Meta. This project is not affiliated with or endorsed by WhatsApp or Meta.
