#!/bin/sh
# usage: transcribe.sh <audio file>   (agent.conf: transcribe=/path/to/transcribe.sh)
# Voice notes are MPEG-4/AAC .m4a (Android MediaRecorder 48 kHz mono, iOS AVAudioRecorder 44.1 kHz stereo).
# Env: WHISPER_BIN (default: whisper-cli or whisper-cpp on PATH), WHISPER_MODEL (default ~/.local/share/whisper.cpp/ggml-base.bin),
#      WHISPER_THREADS (default 4), TRANSCRIBE_MAX_SECONDS (default 300), WHISPER_RUN_SECONDS (default 55), WHISPER_LANG (default auto).
set -eu
in="${1:?usage: transcribe.sh <audio file>}"
bin="${WHISPER_BIN:-$(command -v whisper-cli || command -v whisper-cpp || true)}"
model="${WHISPER_MODEL:-$HOME/.local/share/whisper.cpp/ggml-base.bin}"
max="${TRANSCRIBE_MAX_SECONDS:-300}"
[ -n "$bin" ] || { echo "transcribe.sh: whisper-cli not found (set WHISPER_BIN)" >&2; exit 1; }
[ -r "$model" ] || { echo "transcribe.sh: model not found: $model (set WHISPER_MODEL)" >&2; exit 1; }
command -v ffmpeg >/dev/null 2>&1 || { echo "transcribe.sh: ffmpeg not found" >&2; exit 1; }

if command -v ffprobe >/dev/null 2>&1; then
  seconds=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$in" 2>/dev/null | cut -d. -f1 || true)
  case "$seconds" in ''|*[!0-9]*) seconds=0 ;; esac
  if [ "$seconds" -gt "$max" ]; then
    echo "transcribe.sh: audio is ${seconds}s, limit is ${max}s" >&2
    exit 1
  fi
fi

wav=$(mktemp "${TMPDIR:-/tmp}/transcribe.XXXXXX")
trap 'rm -f "$wav"' EXIT INT TERM
ffmpeg -nostdin -loglevel error -y -t "$max" -i "$in" -ar 16000 -ac 1 -c:a pcm_s16le -f wav "$wav"

"$bin" -m "$model" -f "$wav" -l "${WHISPER_LANG:-auto}" -t "${WHISPER_THREADS:-4}" -nt -np &
pid=$!
waited=0
while kill -0 "$pid" 2>/dev/null; do
  if [ "$waited" -ge "${WHISPER_RUN_SECONDS:-55}" ]; then kill "$pid" 2>/dev/null || true; break; fi
  sleep 1
  waited=$((waited + 1))
done
wait "$pid"
