"""Collects the messages Gnani Evon will label on Kaggle (kaggle/evon_teacher.ipynb). Standard library only.

    python3 evon-app/training/make_pool.py --chat my-chat.txt --me "My Name" --out pool.jsonl

The pool is the Android app's example sentences (tools/commitment_examples.py, read as data; their labels
are left out, because the teacher gives its own) plus the messages YOU sent in any exported WhatsApp chats
you pass (WhatsApp: chat menu -> More -> Export chat -> Without media). Other people's messages are never
included. Use only your own chats: the file is uploaded to your Kaggle account.
"""
import argparse
import ast
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Android: "12/11/2026, 21:45 - Asha: hello"      iOS: "[12/11/26, 9:45:12 PM] Asha: hello"
STAMP = r"\d{1,4}[/.-]\d{1,2}[/.-]\d{2,4},?\s\d{1,2}[:.]\d{2}(?:[:.]\d{2})?\s?(?:[ap]\.?m\.?)?"
HEAD = re.compile(rf"^(?:{STAMP}\s-\s|\[{STAMP}\]\s)(.*)$", re.I)
INVISIBLE = re.compile("[‎‏‪-‮﻿]")
NOT_TEXT = re.compile(
    r"^(<media omitted>|<attached: .*>|null|this message was deleted\.?|you deleted this message\.?|"
    r"(image|video|audio|sticker|gif|document|contact card) omitted|missed (voice|video) call.*)$", re.I)
MAX_CHARS = 300   # the on-device model reads short messages; long pastes are not promises worth learning from


def example_sentences() -> list[tuple[str, bool]]:
    """(text, hand label) for every sentence in the app's example file."""
    out = []
    for node in ast.parse((ROOT / "tools/commitment_examples.py").read_text(encoding="utf-8")).body:
        name = getattr(node.targets[0], "id", "") if isinstance(node, ast.Assign) else ""
        if name in ("COMMITMENTS", "NON_COMMITMENTS"):
            for group in ast.literal_eval(node.value).values():
                out.extend((text, name == "COMMITMENTS") for text in group)
    return out


def read_export(path: Path) -> str:
    data = path.read_bytes()
    if data[:2] == b"PK":  # iOS exports a zip with the chat inside
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = [n for n in archive.namelist() if n.lower().endswith(".txt")]
            if not names:
                raise SystemExit(f"{path}: no .txt chat inside the zip")
            data = archive.read(names[0])
    return data.decode("utf-8-sig")


def messages(text: str) -> list[tuple[str, str]]:
    """Export text -> (sender, message). Lines that don't start a new message continue the one before."""
    out: list[list[str]] = []
    for raw in text.splitlines():
        line = INVISIBLE.sub("", raw).replace(" ", " ").replace(" ", " ").rstrip()
        m = HEAD.match(line)
        if m:
            sender, sep, body = m.group(1).partition(": ")
            out.append([sender.strip(), body] if sep else ["", ""])  # no sender = a system line
        elif out and out[-1][0]:
            out[-1][1] += "\n" + line
    return [(s, b.strip()) for s, b in out if s and b.strip() and not NOT_TEXT.match(b.strip())]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--chat", action="append", default=[], help="an exported WhatsApp chat (.txt or .zip); may be repeated")
    p.add_argument("--me", help="your name exactly as the chat shows it")
    p.add_argument("--no-examples", action="store_true", help="leave out the app's example sentences")
    p.add_argument("--out", default="pool.jsonl")
    args = p.parse_args()
    texts = [] if args.no_examples else [t for t, _ in example_sentences()]
    for path in args.chat:
        if not args.me:
            raise SystemExit('--chat needs --me "Your name exactly as it appears in the chat"')
        found = messages(read_export(Path(path)))
        mine = [body for sender, body in found if sender.casefold() == args.me.casefold() and len(body) <= MAX_CHARS]
        if not mine:
            names = sorted({s for s, _ in found})
            raise SystemExit(f"no messages from {args.me!r} in {path}. Senders in that chat: {names}")
        print(f"{path}: {len(mine)} of your messages")
        texts += mine
    unique = list(dict.fromkeys(t.strip() for t in texts if len(t.strip()) >= 3))
    Path(args.out).write_text("".join(json.dumps({"text": t}, ensure_ascii=False) + "\n" for t in unique), encoding="utf-8")
    print(f"{len(unique)} messages -> {args.out}. Upload it to Kaggle as a private dataset and attach it to the notebook.")


if __name__ == "__main__":
    main()
