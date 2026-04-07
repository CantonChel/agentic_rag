import hashlib
import os
import time

from flask import Flask, jsonify, request


app = Flask(__name__)

STATE = {
    "mode": "normal",
    "delayMs": 0,
    "vectorSize": int(os.getenv("MOCK_EMBEDDING_VECTOR_SIZE", "16")),
}


def _texts_from_payload(payload):
    input_value = payload.get("input", [])
    if isinstance(input_value, str):
        return [input_value]
    if isinstance(input_value, list):
        return ["" if item is None else str(item) for item in input_value]
    return [str(input_value)]


def _build_vector(text, size):
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    out = []
    for idx in range(size):
        value = digest[idx % len(digest)]
        scaled = round((value / 255.0) * 2.0 - 1.0, 6)
        out.append(scaled)
    return out


def _apply_mode():
    mode = STATE["mode"]
    delay_ms = int(STATE["delayMs"])
    if mode == "error":
        return jsonify({"error": {"message": "mock embedding forced error", "type": "mock_error"}}), 500
    if mode == "slow":
        time.sleep(max(delay_ms, 0) / 1000.0)
    if mode == "timeout":
        wait_ms = delay_ms if delay_ms > 0 else 180000
        time.sleep(wait_ms / 1000.0)
    return None


@app.get("/healthz")
def healthz():
    return jsonify(
        {
            "status": "ok",
            "mode": STATE["mode"],
            "delayMs": STATE["delayMs"],
            "vectorSize": STATE["vectorSize"],
        }
    )


@app.route("/admin/mode", methods=["GET", "POST"])
def admin_mode():
    if request.method == "GET":
        return jsonify(STATE)

    payload = request.get_json(silent=True) or {}
    mode = str(payload.get("mode", "normal")).strip().lower()
    if mode not in {"normal", "slow", "error", "timeout"}:
        return jsonify({"error": "unsupported mode"}), 400

    STATE["mode"] = mode
    STATE["delayMs"] = int(payload.get("delayMs", 0) or 0)
    if payload.get("vectorSize") is not None:
        STATE["vectorSize"] = max(1, int(payload["vectorSize"]))
    return jsonify(STATE)


@app.post("/v1/embeddings")
def embeddings():
    maybe_error = _apply_mode()
    if maybe_error is not None:
        return maybe_error

    payload = request.get_json(silent=True) or {}
    texts = _texts_from_payload(payload)
    model = payload.get("model", "mock-embedding-16d")
    vector_size = STATE["vectorSize"]

    data = []
    total_chars = 0
    for index, text in enumerate(texts):
        total_chars += len(text)
        data.append(
            {
                "object": "embedding",
                "index": index,
                "embedding": _build_vector(text, vector_size),
            }
        )

    return jsonify(
        {
            "object": "list",
            "model": model,
            "data": data,
            "usage": {
                "prompt_tokens": total_chars,
                "total_tokens": total_chars,
            },
        }
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
