"""Run: python3 -m unittest companion/test_listener.py  (no heavy dependencies needed)."""
import http.server
import json
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import listener  # noqa: E402


class Mock(http.server.BaseHTTPRequestHandler):
    got = []

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n))
        Mock.got.append((self.path, self.headers.get("X-Kavach-Token"), body))
        self.send_response(200 if self.headers.get("X-Kavach-Token") == "goodtoken1" else 401)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *a):
        pass


class ListenerTest(unittest.TestCase):
    def setUp(self):
        Mock.got.clear()
        self.srv = http.server.HTTPServer(("127.0.0.1", 0), Mock)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_port}/transcript"

    def tearDown(self):
        self.srv.shutdown()

    def test_posts_text_with_token(self):
        self.assertEqual(200, listener.post_transcript(self.url, "goodtoken1", "you will be arrested", "en"))
        self.assertEqual(("/transcript", "goodtoken1", {"text": "you will be arrested", "language": "en"}), Mock.got[0])

    def test_bad_token_is_401(self):
        self.assertEqual(401, listener.post_transcript(self.url, "wrongtoken", "hi"))

    def test_unreachable_phone_returns_zero_not_a_crash(self):
        self.assertEqual(0, listener.post_transcript("http://127.0.0.1:1/transcript", "goodtoken1", "x", timeout=0.5))


if __name__ == "__main__":
    unittest.main()
