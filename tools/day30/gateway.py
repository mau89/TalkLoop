#!/usr/bin/env python3
"""Small, authenticated HTTPS facade for a single local Ollama model."""
import collections
import hmac
import json
import math
import os
import signal
import ssl
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL = "qwen2.5:0.5b"
MAX_CONTEXT = 8192
MAX_OUTPUT = 256
MAX_BODY = 32768
RATE_LIMIT = 6
RATE_WINDOW = 60


class Rejected(Exception):
    def __init__(self, status, message, retry=None):
        self.status, self.message, self.retry = status, message, retry


def integer(value, low, high, name):
    if type(value) is not int or not low <= value <= high:
        raise Rejected(400, f"{name}: expected integer {low}..{high}")
    return value


def validate_chat(body):
    if not isinstance(body, dict) or set(body) - {"model", "messages", "stream", "think", "format", "options"}:
        raise Rejected(400, "Unsupported request fields")
    if body.get("model") != MODEL:
        raise Rejected(400, f"Only {MODEL} is allowed")
    if body.get("stream", False) is not False or body.get("think", False) is not False:
        raise Rejected(400, "Only stream=false and think=false are supported")
    messages = body.get("messages")
    if not isinstance(messages, list) or not 1 <= len(messages) <= 32:
        raise Rejected(400, "Expected 1..32 messages")
    for message in messages:
        if (not isinstance(message, dict) or set(message) != {"role", "content"}
                or message["role"] not in ("system", "user", "assistant")
                or not isinstance(message["content"], str)):
            raise Rejected(400, "Invalid message")
    options = body.get("options", {})
    if not isinstance(options, dict) or set(options) - {
        "num_ctx", "num_predict", "num_thread", "temperature", "presence_penalty", "stop"
    }:
        raise Rejected(400, "Unsupported options")
    options = dict(options)
    context = integer(options.get("num_ctx", MAX_CONTEXT), 1, MAX_CONTEXT, "num_ctx")
    output = integer(options.get("num_predict", MAX_OUTPUT), 1, MAX_OUTPUT, "num_predict")
    integer(options.get("num_thread", 1), 1, 1, "num_thread")
    for name, low, high in (("temperature", 0, 2), ("presence_penalty", -2, 2)):
        value = options.get(name)
        if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high):
            raise Rejected(400, f"Invalid {name}")
    stop = options.get("stop")
    if stop is not None and (not isinstance(stop, list) or len(stop) > 8
                             or any(not isinstance(s, str) or len(s) > 128 for s in stop)):
        raise Rejected(400, "Invalid stop sequences")
    schema = body.get("format")
    if schema is not None and not isinstance(schema, dict):
        raise Rejected(400, "format must be a JSON schema object")
    # Same conservative UTF-8 preflight as TalkLoop, with extra role overhead.
    try:
        estimate = 32 + sum(len(m["content"].encode("utf-8")) + 16 for m in messages)
        if schema is not None:
            estimate += len(json.dumps(schema, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    except UnicodeError:
        raise Rejected(400, "Invalid Unicode text")
    if estimate + output > context:
        raise Rejected(413, "Context budget exceeded; shorten the conversation or start a new task")
    options.update(num_ctx=context, num_predict=output, num_thread=1)
    request = dict(body, options=options, stream=False, think=False, keep_alive="5m")
    return request


class Gateway:
    def __init__(self, token, upstream="http://127.0.0.1:11434", clock=time.monotonic):
        if len(token) < 32 or not token.isascii():
            raise ValueError("Service key must contain at least 32 ASCII characters")
        self.token, self.upstream, self.clock = token, upstream.rstrip("/"), clock
        self.lock = threading.Lock()
        self.started = collections.deque()
        self.busy = False
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def authorize(self, authorization):
        if not hmac.compare_digest(authorization.encode("utf-8"), ("Bearer " + self.token).encode("ascii")):
            raise Rejected(401, "Invalid service key")

    def admit(self):
        with self.lock:
            now = self.clock()
            while self.started and self.started[0] <= now - RATE_WINDOW:
                self.started.popleft()
            if self.busy:
                raise Rejected(429, "Model busy; retry after the current response", 5)
            if len(self.started) >= RATE_LIMIT:
                raise Rejected(429, "Rate limit: 6 generation requests per minute", max(1, math.ceil(RATE_WINDOW - now + self.started[0])))
            self.started.append(now)
            self.busy = True

    def release(self):
        with self.lock:
            self.busy = False

    def proxy(self, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.upstream + path, data=data,
                                         headers={"Content-Type": "application/json"})
        try:
            with self.http.open(request, timeout=180 if body else 5) as response:
                raw = response.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise Rejected(502, "Upstream response too large")
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise Rejected(502, "Invalid upstream response")
                return result
        except urllib.error.HTTPError as error:
            raise Rejected(503 if error.code == 503 else 502, "Local Ollama rejected the request") from error
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            raise Rejected(502, "Local Ollama unavailable or response invalid") from error


class Handler(BaseHTTPRequestHandler):
    server_version = "TalkLoopPrivateAI"

    def log_message(self, *_):
        pass  # Do not write prompts, headers, keys or URL query strings to logs.

    def send_json(self, status, body, retry=None):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        if retry is not None:
            self.send_header("Retry-After", str(retry))
        self.end_headers()
        self.wfile.write(raw)

    def dispatch(self):
        gateway = self.server.gateway
        try:
            gateway.authorize(self.headers.get("Authorization", ""))
            if self.command == "GET" and self.path == "/health":
                gateway.proxy("/api/version")
                self.send_json(200, {"service": "TalkLoop private AI", "model": MODEL,
                                     "max_context": MAX_CONTEXT, "max_output": MAX_OUTPUT,
                                     "rate_limit_per_minute": RATE_LIMIT})
            elif self.command == "GET" and self.path == "/api/tags":
                tags = gateway.proxy("/api/tags")
                self.send_json(200, {"models": [m for m in tags.get("models", [])
                                                if m.get("name") == MODEL or m.get("model") == MODEL]})
            elif self.command == "POST" and self.path == "/api/chat":
                if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding"):
                    raise Rejected(400, "Encoded requests are not supported")
                if self.headers.get_content_type() != "application/json":
                    raise Rejected(415, "Expected application/json")
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise Rejected(400, "Invalid Content-Length")
                if not 0 < length <= MAX_BODY:
                    raise Rejected(413, "Request body limit: 32768 bytes")
                try:
                    body = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeError):
                    raise Rejected(400, "Invalid JSON")
                request = validate_chat(body)
                gateway.admit()
                try:
                    result = gateway.proxy("/api/chat", request)
                finally:
                    gateway.release()
                self.send_json(200, result)
            else:
                raise Rejected(404, "Endpoint not available")
        except Rejected as error:
            self.close_connection = True
            self.send_json(error.status, {"error": error.message}, error.retry)

    do_GET = dispatch
    do_POST = dispatch


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, gateway, tls=None):
        self.gateway, self.tls = gateway, tls
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            request.settimeout(10)
            if self.tls:
                request = self.tls.wrap_socket(request, server_side=True)
            super().process_request_thread(request, address)
        except (ssl.SSLError, OSError):
            self.shutdown_request(request)
        finally:
            self.slots.release()


def main():
    token = Path(os.environ["TOKEN_FILE"]).read_text().strip()
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(os.environ["TLS_CERT"], os.environ["TLS_KEY"])
    # Reload renewed certificates without interrupting in-flight generations.
    signal.signal(signal.SIGHUP, lambda *_: tls.load_cert_chain(os.environ["TLS_CERT"], os.environ["TLS_KEY"]))
    server = Server(("0.0.0.0", 18443), Gateway(token), tls)
    print("TalkLoop private AI: HTTPS :18443; context <=8192; output <=256; 6/min", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
