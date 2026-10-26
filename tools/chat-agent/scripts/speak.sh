#!/bin/sh
# usage: speak.sh <text file> <out file>   (agent.conf: tts=/path/to/speak.sh)
# Writes an MPEG-4/AAC .m4a voice note (48 kHz mono, what the apps record; see the transcribe.sh header).
# The agent passes TTS_VOICE, TTS_PRESET (stable|expressive), TTS_REFERENCE and TTS_REFERENCE_TEXT in the environment.
# Backend A (sidecar): TTS_SOCKET=/run/quag-tts/tts.sock, see deploy/tts/. Needs curl and jq.
# Backend B (local):   TTS_CMD, e.g. "uv run --project ~/Github/kittytts-voice-exp python speak.py"; it is run as
#                      $TTS_CMD "<text>" -o <wav> [--voice V] [--preset P] [--reference R] [--reference-text T].
# Env: TTS_RUN_SECONDS (default 230, sidecar request limit; keep it under the agent's ttsTimeout).
set -eu
in="${1:?usage: speak.sh <text file> <out file>}"
out="${2:?usage: speak.sh <text file> <out file>}"
[ -s "$in" ] || { echo "speak.sh: empty text file: $in" >&2; exit 1; }
command -v ffmpeg >/dev/null 2>&1 || { echo "speak.sh: ffmpeg not found" >&2; exit 1; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/speak.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM
wav="$tmp/out.wav"

if [ -n "${TTS_SOCKET:-}" ]; then
  command -v curl >/dev/null 2>&1 || { echo "speak.sh: curl not found" >&2; exit 1; }
  command -v jq >/dev/null 2>&1 || { echo "speak.sh: jq not found" >&2; exit 1; }
  jq -n --rawfile text "$in" --arg voice "${TTS_VOICE:-}" --arg preset "${TTS_PRESET:-}" \
    --arg reference "${TTS_REFERENCE:-}" --arg referenceText "${TTS_REFERENCE_TEXT:-}" \
    '{text: $text} + ({voice: $voice, preset: $preset, reference: $reference, referenceText: $referenceText} | with_entries(select(.value != "")))' > "$tmp/req.json"
  curl --fail -sS --max-time "${TTS_RUN_SECONDS:-230}" --unix-socket "$TTS_SOCKET" \
    -H 'content-type: application/json' --data-binary @"$tmp/req.json" http://tts/speak -o "$wav"
elif [ -n "${TTS_CMD:-}" ]; then
  set -- "$(cat "$in")" -o "$wav"
  [ -z "${TTS_VOICE:-}" ] || set -- "$@" --voice "$TTS_VOICE"
  [ -z "${TTS_PRESET:-}" ] || set -- "$@" --preset "$TTS_PRESET"
  [ -z "${TTS_REFERENCE:-}" ] || set -- "$@" --reference "$TTS_REFERENCE"
  [ -z "${TTS_REFERENCE_TEXT:-}" ] || set -- "$@" --reference-text "$TTS_REFERENCE_TEXT"
  # shellcheck disable=SC2086
  $TTS_CMD "$@"
else
  echo "speak.sh: set TTS_SOCKET (sidecar) or TTS_CMD (local command)" >&2
  exit 1
fi

[ -s "$wav" ] || { echo "speak.sh: the synthesizer wrote no audio" >&2; exit 1; }
ffmpeg -nostdin -loglevel error -y -i "$wav" -ac 1 -ar 48000 -c:a aac -b:a 48k -movflags +faststart -f mp4 "$out"
