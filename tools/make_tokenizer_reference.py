"""Writes expected token ids from the reference Hugging Face `tokenizers` library for SentencePieceTokenizerTest.

Runs on the development machine only, against the bundled tokenizer.json. Nothing here ships in the app.
Usage: .venv/bin/python tools/make_tokenizer_reference.py
"""
import ast
import json
import random
from pathlib import Path

import tokenizers
from tokenizers import Tokenizer

root = Path(__file__).resolve().parents[1]
tokenizer = Tokenizer.from_file(str(root / "app/src/main/assets/semantic/tokenizer.json"))


def string_literals(path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    return [node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)]


def kotlin_string_literals(path):
    import re
    return [json.loads(f'"{m}"') for m in re.findall(r'"((?:[^"\\\n$]|\\.)*)"', path.read_text(encoding="utf-8"))]


texts = []
texts += string_literals(root / "tools/commitment_examples.py")
texts += kotlin_string_literals(root / "app/src/test/java/com/satyam/whatsappbot/domain/CommitmentAndTimeTest.kt")
texts += kotlin_string_literals(root / "app/src/main/java/com/satyam/whatsappbot/domain/SemanticCommitmentDetector.kt")
texts += [
    "", " ", "   \t\n ", "kal bhej dunga pakka", "I'll send it tomorrow 😀", "café  ｆｕｌｌ ﬁ ①",
    "मैं भेज दूंगा", "a<s>b <mask> c", "</s></s><pad><unk>", "x y\tz\n", "é ä ñ",
    "HELLO World MiXeD", "👍🏽 👨‍👩‍👧 ❤️ 1️⃣ 🇮🇳", "Call me at 9876543210 or mail a.b@example.com",
    "https://example.com/path?q=1&x=2", "₹500 bhej dena 50% off!!!", "ok\r\nok\rok ok　ok",
    "​zero‍width‌join", "control\u0000\u0001\u0007chars", "tab\u000bvt\u000cff",
    "日本語のテキスト", "中文消息明天发", "مرحبا كيف حالك", "Привет, отправлю завтра", "Ελληνικά",
    "ক্ষমা করবেন, কাল পাঠাব", "நாளை அனுப்புவேன்", "ⅫⅣ ㎏ ㍿ ℃ ™ ½", "▁already▁metaspace ▁ ▁▁x",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    " ".join(["bhej dunga kal pakka"] * 80),
    "\U0001F9EA\U0001FAE0\U000E0001 rare planes \U00010348",
]

# Fuzz: random mixes of ASCII, Latin-1, Devanagari, emoji, CJK, combining marks and odd whitespace.
pools = [
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!?'\"-_:;()",
    "àáâãäåæçèéêëìíîïñòóôõöøùúûüýÿßÀÉÎÕÜ",
    "अआइईउऊएऐओऔकखगघचछजझटठडढणतथदधनपफबभमयरलवशषसहािीुूेैोौंःँ़्",
    "😀😂🤣😊😍🙏👍👎🔥💯✅❌⏰📞📄",
    "的一是不了人我在有他这中大来上们为到说国和地出道也时年",
    "̧̀́̂̃̈‍‌️",
    " \t\n  　 ",
    "ｆｕｌｌｗｉｄｔｈＡＢＣ１２３ﬁﬂ①②③™℃㎏",
]
rng = random.Random(20261018)
for _ in range(3000):
    length = rng.randint(1, 60)
    texts.append("".join(rng.choice(rng.choice(pools)) for _ in range(length)))

seen = set()
cases = []
for text in texts:
    if text in seen:
        continue
    seen.add(text)
    cases.append({"text": text, "ids": tokenizer.encode(text).ids})

out = root / "app/src/test/resources/tokenizer_reference.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({"tokenizers_version": tokenizers.__version__, "cases": cases}, ensure_ascii=False), encoding="utf-8")
print(f"wrote {len(cases)} cases to {out} with tokenizers {tokenizers.__version__}")
