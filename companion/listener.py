#!/usr/bin/env python3
"""Kavach companion listener (audio option C).

Sits next to the victim's phone (call on speaker), transcribes the room with faster-whisper and POSTs text to the Kavach app's
companion server. Use it when this phone's OS silences the microphone during calls (see the Day-0 audio test).

Safest wiring is USB, so nothing is exposed on Wi-Fi:
    adb forward tcp:8765 tcp:8765
    python3 listener.py --token <same token as in the app's Settings>

Test without a microphone or model (type lines, they are posted as if heard):
    python3 listener.py --token <token> --stdin

Only text leaves this script. Audio is never written to disk or sent anywhere.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def post_transcript(url: str, token: str, text: str, language: str = "auto", timeout: float = 4.0) -> int:
    """POST one transcript chunk. Returns the HTTP status (0 on connection failure)."""
    body = json.dumps({"text": text, "language": language}).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": "application/json", "X-Kavach-Token": token})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except (urllib.error.URLError, OSError):
        return 0


def run_stdin(args) -> None:
    print("Type a line and press Enter to send it as caller speech. Ctrl-D to quit.", file=sys.stderr)
    for line in sys.stdin:
        line = line.strip()
        if line:
            print(post_transcript(args.url, args.token, line, args.language), line)


def run_mic(args) -> None:
    import numpy as np  # imported lazily so --stdin works without heavy deps
    import sounddevice as sd
    from faster_whisper import WhisperModel

    model = WhisperModel(args.model, device="cpu", compute_type="int8")
    sr, win, hop = 16000, 5, 4  # 5 s windows, 1 s overlap, like the phone app
    print(f"Listening ({args.model}, {args.language}). Ctrl-C to stop.", file=sys.stderr)
    buf = np.zeros(sr * win, dtype=np.float32)
    last = ""
    with sd.InputStream(samplerate=sr, channels=1, dtype="float32", device=args.device) as stream:
        while True:
            chunk, _ = stream.read(sr * hop)
            buf = np.concatenate([buf[sr * hop:], chunk[:, 0]])
            if float(np.sqrt(np.mean(buf ** 2))) < 0.003:  # silence: skip
                continue
            segs, info = model.transcribe(buf, language=None if args.language == "auto" else args.language, vad_filter=True, beam_size=1)
            text = " ".join(s.text.strip() for s in segs).strip()
            if text and text != last:
                last = text
                print(post_transcript(args.url, args.token, text, info.language or args.language), text)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://127.0.0.1:8765/transcript")
    ap.add_argument("--token", required=True, help="shared secret, at least 8 characters, same as in the app")
    ap.add_argument("--model", default="small", help="faster-whisper model: base is faster, small is better for Hinglish")
    ap.add_argument("--language", default="auto", help="auto | hi | en")
    ap.add_argument("--device", default=None, help="sounddevice input device index or name")
    ap.add_argument("--stdin", action="store_true", help="read lines from stdin instead of the microphone")
    args = ap.parse_args()
    if len(args.token) < 8:
        sys.exit("token must be at least 8 characters")
    run_stdin(args) if args.stdin else run_mic(args)


if __name__ == "__main__":
    main()
