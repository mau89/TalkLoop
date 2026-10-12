import json
import ssl
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from gateway import Gateway, MODEL, Rejected, Server, validate_chat

TOKEN = "test-key-only-012345678901234567890123456789"


def chat():
    return {"model": MODEL, "stream": False, "think": False,
            "messages": [{"role": "system", "content": "Отвечай кратко"},
                         {"role": "user", "content": "Столица Франции?"}],
            "options": {"num_ctx": 8192, "num_predict": 256, "temperature": 0,
                        "presence_penalty": 0, "stop": ["END"]}}


class LimitsTest(unittest.TestCase):
    def test_app_system_history_schema_and_options_are_preserved(self):
        body = chat()
        body["messages"].extend([{"role": "assistant", "content": "Париж"},
                                  {"role": "user", "content": "Повтори"}])
        body["format"] = {"type": "object", "properties": {"answer": {"type": "string"}}}
        result = validate_chat(body)
        self.assertEqual(body["messages"], result["messages"])
        self.assertEqual(body["format"], result["format"])
        self.assertEqual(1, result["options"]["num_thread"])
        self.assertEqual(["END"], result["options"]["stop"])

    def test_oversized_russian_context_and_schema_are_rejected(self):
        for field in ("messages", "format"):
            body = chat()
            if field == "messages":
                body["messages"][1]["content"] = "Я" * 5000
            else:
                body["format"] = {"description": "Я" * 5000}
            with self.assertRaises(Rejected) as caught:
                validate_chat(body)
            self.assertEqual(413, caught.exception.status)

    def test_generation_limits_cannot_be_overridden(self):
        for options in ({"num_ctx": 8193}, {"num_predict": -1}, {"num_predict": 257},
                        {"num_thread": 4}, {"num_ctx": True}, {"temperature": float("nan")},
                        {"num_batch": 9999}, {"stop": "END"}):
            body = chat()
            body["options"] = options
            with self.subTest(options=options), self.assertRaises(Rejected):
                validate_chat(body)

    def test_cloud_models_tools_and_streaming_are_rejected(self):
        for patch in ({"model": "model:cloud"}, {"stream": True}, {"think": True},
                      {"tools": []}, {"options": []}):
            with self.subTest(patch=patch), self.assertRaises(Rejected):
                validate_chat(dict(chat(), **patch))

    def test_busy_and_rate_limits_recover(self):
        now = [0.0]
        gateway = Gateway(TOKEN, clock=lambda: now[0])
        gateway.admit()
        with self.assertRaises(Rejected) as busy:
            gateway.admit()
        self.assertEqual(429, busy.exception.status)
        gateway.release()
        for _ in range(5):
            gateway.admit()
            gateway.release()
        with self.assertRaises(Rejected) as rate:
            gateway.admit()
        self.assertEqual(60, rate.exception.retry)
        now[0] = 60
        gateway.admit()
        gateway.release()


class Upstream(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_):
        pass

    def respond(self, body):
        raw = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self.respond({"models": [{"name": MODEL}, {"name": "other:cloud"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.requests.append(body)
        if body["messages"][-1]["content"] == "fail-upstream":
            self.send_error(503)
            return
        self.respond({"model": MODEL, "message": {"role": "assistant", "content": "Париж"},
                      "done": True, "done_reason": "stop", "prompt_eval_count": 30, "eval_count": 5})


class HttpsTest(unittest.TestCase):
    def setUp(self):
        self.gateway.started.clear()
        self.gateway.busy = False

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        folder = Path(cls.temp.name)
        cert, key = folder / "cert.pem", folder / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-keyout", str(key), "-out", str(cert), "-days", "1",
                        "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        cls.upstream_thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.upstream_thread.start()
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        cls.gateway = Gateway(TOKEN, f"http://127.0.0.1:{cls.upstream.server_port}")
        cls.server = Server(("127.0.0.1", 0), cls.gateway, tls)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"https://127.0.0.1:{cls.server.server_port}"
        cls.http = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cert))))

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.upstream.shutdown()
        cls.upstream.server_close()
        cls.thread.join()
        cls.upstream_thread.join()
        cls.temp.cleanup()

    def request(self, path, body=None, token=TOKEN):
        request = urllib.request.Request(self.url + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        return self.http.open(request, timeout=5)

    def test_native_api_works_through_verified_https(self):
        with self.request("/api/tags") as response:
            self.assertEqual([{"name": MODEL}], json.load(response)["models"])
        with self.request("/api/chat", chat()) as response:
            result = json.load(response)
            self.assertEqual("Париж", result["message"]["content"])
            self.assertEqual(5, result["eval_count"])
        self.assertEqual(chat()["messages"], Upstream.requests[-1]["messages"])
        self.assertFalse(self.gateway.busy)

    def test_unauthorized_and_oversized_requests_never_reach_model(self):
        before = len(Upstream.requests)
        with self.assertRaises(urllib.error.HTTPError) as unauthorized:
            self.request("/api/chat", chat(), token="wrong")
        self.assertEqual(401, unauthorized.exception.code)
        oversized = chat()
        oversized["messages"][1]["content"] = "x" * 9000
        with self.assertRaises(urllib.error.HTTPError) as context:
            self.request("/api/chat", oversized)
        self.assertEqual(413, context.exception.code)
        self.assertEqual(before, len(Upstream.requests))

    def test_concurrent_request_is_rejected_and_model_is_reusable(self):
        self.gateway.admit()
        try:
            with self.assertRaises(urllib.error.HTTPError) as busy:
                self.request("/api/chat", chat())
            self.assertEqual(429, busy.exception.code)
        finally:
            self.gateway.release()
        with self.request("/api/chat", chat()) as response:
            self.assertEqual(200, response.status)

    def test_upstream_failure_releases_generation_slot(self):
        body = chat()
        body["messages"][-1]["content"] = "fail-upstream"
        with self.assertRaises(urllib.error.HTTPError) as failure:
            self.request("/api/chat", body)
        self.assertEqual(503, failure.exception.code)
        self.assertFalse(self.gateway.busy)
        with self.request("/api/chat", chat()) as response:
            self.assertEqual(200, response.status)


if __name__ == "__main__":
    unittest.main()
