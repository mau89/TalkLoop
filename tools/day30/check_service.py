#!/usr/bin/env python3
"""Run on Mac: verified external HTTPS, actual model replies and limit probes."""
import concurrent.futures
import getpass
import json
import sys
import threading
import time
import urllib.error
import urllib.request

MODEL = "qwen2.5:0.5b"


def main():
    endpoint = sys.argv[1] if len(sys.argv) > 1 else "https://89.40.70.118:18443"
    if not endpoint.startswith("https://"):
        raise SystemExit("Use HTTPS")
    token = getpass.getpass("Ключ сервиса (ввод скрыт): ").strip()
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, key=token):
        started = time.monotonic()
        call = urllib.request.Request(endpoint.rstrip("/") + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with http.open(call, timeout=240) as response:
                return {"status": response.status, "seconds": round(time.monotonic() - started, 3), "body": json.load(response)}
        except urllib.error.HTTPError as error:
            return {"status": error.code, "seconds": round(time.monotonic() - started, 3), "body": json.load(error)}

    def chat(text, **options):
        return {"model": MODEL, "stream": False, "think": False,
                "messages": [{"role": "user", "content": text}],
                "options": dict(num_ctx=8192, num_predict=64, temperature=0, **options)}

    results = {"endpoint": endpoint, "health": request("/health"),
               "wrong_key": request("/api/tags", key="wrong-key"),
               "tags": request("/api/tags"), "sequential": []}
    for prompt in ("Назови столицу Франции одним словом.",
                   "Сколько будет 17 * 23? Ответь числом.",
                   "Объясни в двух коротких предложениях, чем сервер отличается от приложения."):
        result = request("/api/chat", chat(prompt))
        results["sequential"].append(dict(prompt=prompt, **result))
        print(json.dumps(results["sequential"][-1], ensure_ascii=False), flush=True)
    barrier = threading.Barrier(2)

    def parallel_probe(_):
        barrier.wait()
        return request("/api/chat", chat("Перечисли восемь способов проверить приложение. Коротко."))

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results["parallel"] = list(pool.map(parallel_probe, range(2)))
    oversized = chat("Я" * 5000)
    results["oversized_context"] = request("/api/chat", oversized)
    results["too_many_tokens"] = request("/api/chat", dict(chat("Привет"), options={"num_predict": 257}))
    results["rate_probes"] = []
    # Stop on the first rate rejection. If responses take >60s, report observations
    # rather than pretending that the rolling window was exhausted.
    for _ in range(3):
        body = chat("Ответь только словом ОК.")
        body["options"]["num_predict"] = 8
        result = request("/api/chat", body)
        results["rate_probes"].append(result)
        if result["status"] == 429:
            break
    results["checks"] = {
        "https_and_auth": results["health"]["status"] == 200 and results["wrong_key"]["status"] == 401,
        "three_model_replies": all(r["status"] == 200 and r["body"].get("done")
            and r["body"].get("message", {}).get("content", "").strip() for r in results["sequential"]),
        "context_rejected": results["oversized_context"]["status"] == 413,
        "output_limit_rejected": results["too_many_tokens"]["status"] == 400,
        "parallel_busy_observed": sorted(r["status"] for r in results["parallel"]) == [200, 429],
        "rate_limit_observed": any(r["status"] == 429 for r in results["rate_probes"]),
    }
    print(json.dumps(results, ensure_ascii=False, indent=2))
    if not all(results["checks"].values()):
        raise SystemExit("Не все проверки подтверждены. Смотри checks; при 429 в начале дождись свободного минутного окна.")


if __name__ == "__main__":
    main()
