#!/usr/bin/env python3
"""Host sidecar: KittenTTS behind a unix socket. stdlib plus kittenml (and soundfile) from the venv.

POST /speak {"text", "voice"?, "preset"?, "reference"?, "referenceText"?} -> audio/wav (24 kHz mono)
GET  /health -> {"loaded": bool, "busy": bool}

Socket: systemd socket activation (LISTEN_FDS, fd 3) or --socket PATH.
Env: TTS_REPO (experiment repo, default ~/Github/kittytts-voice-exp), TTS_CACHE_DIR (default $TTS_REPO/.cache/huggingface),
     TTS_THREADS (6), TTS_IDLE_EXIT seconds (600), TTS_REFERENCE_DIR (reference wavs must live under it; unset = cloning off),
     TTS_MODEL (model id passed to kittenml, default the one speak.py uses).
"""
import argparse
import io
import json
import os
import re
import socket
import socketserver
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler

THREADS = os.environ.get("TTS_THREADS", "6")
for var in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
    os.environ.setdefault(var, THREADS)
os.environ.setdefault("HF_HUB_OFFLINE", "1")

REPO = os.path.expanduser(os.environ.get("TTS_REPO", "~/Github/kittytts-voice-exp"))
CACHE_DIR = os.path.expanduser(os.environ.get("TTS_CACHE_DIR", os.path.join(REPO, ".cache", "huggingface")))
IDLE_EXIT = float(os.environ.get("TTS_IDLE_EXIT", "600"))
REFERENCE_DIR = os.environ.get("TTS_REFERENCE_DIR")
MODEL_ID = os.environ.get("TTS_MODEL")
MAX_CHARS = 600
MAX_BODY = 16 * 1024
MAX_WAITING = 2
PRESETS = {"stable", "expressive"}
NAME = re.compile(r"^[A-Za-z0-9_ -]{1,40}$")
SAMPLE_RATE = 24000

state = threading.Lock()
work = threading.Lock()
waiting = 0
busy = False
last_activity = time.monotonic()
model = None


def log(line):
    print(line, file=sys.stderr, flush=True)


# The only code that touches kittenml. Keep it in step with speak.py in the experiment repo.
def load_model():
    from kittenml import KittenTTS

    args = {"cache_dir": CACHE_DIR}
    return KittenTTS(MODEL_ID, **args) if MODEL_ID else KittenTTS(**args)


def synthesize(tts, text, voice, preset, reference, reference_text):
    if reference:
        return tts.generate(text, reference=reference, reference_text=reference_text, preset=preset)
    return tts.generate(text, voice=voice, preset=preset)


def to_wav(audio):
    import soundfile

    buf = io.BytesIO()
    soundfile.write(buf, audio, SAMPLE_RATE, format="WAV", subtype="PCM_16")
    return buf.getvalue()


class Reject(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def reference_path(raw):
    if not REFERENCE_DIR:
        raise Reject(400, "voice cloning is disabled (TTS_REFERENCE_DIR is not set)")
    root = os.path.realpath(REFERENCE_DIR)
    path = os.path.realpath(raw)
    if os.path.commonpath([root, path]) != root or not os.path.isfile(path):
        raise Reject(400, "reference must be a file under the reference directory")
    return path


def parse(body):
    try:
        req = json.loads(body)
    except ValueError:
        raise Reject(400, "body is not JSON")
    if not isinstance(req, dict):
        raise Reject(400, "body must be an object")
    text = req.get("text")
    if not isinstance(text, str) or not text.strip():
        raise Reject(400, "text is empty")
    if len(text) > MAX_CHARS:
        raise Reject(413, "text is over %d characters" % MAX_CHARS)
    voice = req.get("voice") or "Bruno"
    preset = req.get("preset") or "expressive"
    if not isinstance(voice, str) or not NAME.match(voice):
        raise Reject(400, "bad voice")
    if preset not in PRESETS:
        raise Reject(400, "preset must be stable or expressive")
    reference = req.get("reference")
    reference_text = req.get("referenceText") or None
    if reference:
        reference = reference_path(str(reference))
    return text.strip(), voice, preset, reference or None, reference_text


class Handler(BaseHTTPRequestHandler):
    server_version = "quag-tts"

    def log_message(self, fmt, *args):
        log("%s %s" % (self.command, fmt % args))

    def reply(self, status, body, content_type="application/json"):
        if isinstance(body, (dict, str)):
            body = json.dumps(body if isinstance(body, dict) else {"error": body}).encode()
        self.send_response(status)
        self.send_header("content-type", content_type)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/health":
            return self.reply(404, "not found")
        self.reply(200, {"loaded": model is not None, "busy": busy})

    def do_POST(self):
        global waiting, busy, model, last_activity
        if self.path != "/speak":
            return self.reply(404, "not found")
        try:
            length = int(self.headers.get("content-length") or 0)
            if length <= 0 or length > MAX_BODY:
                raise Reject(413, "body size")
            text, voice, preset, reference, reference_text = parse(self.rfile.read(length))
        except Reject as e:
            return self.reply(e.status, str(e))
        with state:
            last_activity = time.monotonic()
            if waiting >= MAX_WAITING:
                return self.reply(503, "voice is busy")
            waiting += 1
        try:
            with work:
                with state:
                    waiting -= 1
                    busy = True
                started = time.monotonic()
                try:
                    if model is None:
                        log("loading model")
                        model = load_model()
                        log("model loaded in %.1fs" % (time.monotonic() - started))
                    audio = synthesize(model, text, voice, preset, reference, reference_text)
                    wav = to_wav(audio)
                    log("spoke %d chars as %s/%s in %.1fs" % (len(text), "reference" if reference else voice, preset, time.monotonic() - started))
                except Exception as e:
                    log("synthesis failed: %r" % (e,))
                    return self.reply(500, "synthesis failed")
                finally:
                    with state:
                        busy = False
                        last_activity = time.monotonic()
            self.reply(200, wav, "audio/wav")
        except (BrokenPipeError, ConnectionResetError):
            log("client went away")


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


def listening_socket(path):
    if os.environ.get("LISTEN_PID") == str(os.getpid()) and int(os.environ.get("LISTEN_FDS", "0")) >= 1:
        return socket.fromfd(3, socket.AF_UNIX, socket.SOCK_STREAM)
    if not path:
        sys.exit("no LISTEN_FDS from systemd and no --socket PATH")
    if os.path.exists(path):
        os.unlink(path)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.bind(path)
    os.chmod(path, 0o660)
    sock.listen(8)
    return sock


def idle_watch(server):
    while True:
        time.sleep(min(15.0, max(1.0, IDLE_EXIT / 4)))
        with state:
            idle = time.monotonic() - last_activity
            if not busy and waiting == 0 and idle >= IDLE_EXIT:
                break
    log("idle for %.0fs, exiting to free memory" % idle)
    server.shutdown()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--socket", help="unix socket path when not socket-activated")
    args = ap.parse_args()
    sock = listening_socket(args.socket)
    server = Server(sock.getsockname(), Handler, bind_and_activate=False)
    server.socket.close()
    server.socket = sock
    threading.Thread(target=idle_watch, args=(server,), daemon=True).start()
    log("listening, idle exit after %.0fs, %s threads" % (IDLE_EXIT, THREADS))
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
