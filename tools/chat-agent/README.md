# chat-agent

Headless Homebase Chat agent: reads/sends messages and replies when its profile is
summoned (nickname, or its own identity for a bot). Runs a "brain" command per trigger.

## Build

    export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # JDK 21 required
    ./gradlew :chat-agent:installDist
    tools/chat-agent/build/install/chat-agent/bin/chat-agent <command> ...

`chat-agent --version` prints the git sha and build date.

Data lives in `<base>/<profile>/` (credentials, `agent.conf`, `processed.txt`, `runs.txt`, `logs/agent.log`):

| OS | base |
|---|---|
| macOS | `~/Library/Application Support/HomebaseChatAgent` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/homebase-chat-agent` |
| Windows | `%APPDATA%\HomebaseChatAgent` |
| any | `$CHAT_AGENT_HOME` overrides the base |

Credentials are AES-GCM encrypted with a key stored next to them (fixed keystore password, nothing derived
from the machine), so copying a profile dir to another machine works; do that only over a channel you trust.

## Login

    chat-agent login --profile me  --identity you.homebase.id    # delegate: acts as the owner
    chat-agent login --profile bot --identity bot.homebase.id    # bot: its own identity

### Logging in on a headless machine or in a container

The browser that approves the app does not have to be on the same machine.

    chat-agent login --profile bot --identity bot.homebase.id --no-browser

1. The command prints the authorize URL (and a terminal QR if `qrencode` is on PATH). Open it on any device and approve.
2. The browser is redirected to `http://localhost:<port>/authorization-code-callback?...`, which fails to load there.
   Copy that address from the address bar and paste it into the terminal (the query string alone also works).
   Nothing in it is secret; the private key never leaves the agent process.
3. A wrong or garbled paste prints an error and asks again. The HTTP callback and the paste race; the first valid one wins.

The browser is opened automatically only on macOS (`open`) and on Linux with `DISPLAY`/`WAYLAND_DISPLAY` set (`xdg-open`);
`--no-browser` never tries.

Alternative: pin the callback port and tunnel it, so the approving browser's redirect reaches the agent directly:

    chat-agent login --profile bot --identity bot.homebase.id --callback-port 8765 --no-browser
    ssh -L 8765:localhost:8765 user@headless-box      # from the machine with the browser

In a container, use `docker run -it` / `podman run -it` (the paste needs a TTY on stdin), or publish the port with
`-p 8765:8765` and pass `--callback-port 8765`.

`me` triggers on the nickname, and (only while away) on a plain @owner mention. It sends to
note-to-self, and to conversations explicitly listed by uuid in `allowConversations`;
`allowConversations=member` is refused for `me`. Any member of a listed group may summon it
(rate limits apply); note-to-self stays owner-only. The owner's own group messages never trigger.

Away mode: send `@<nick> away` / `@<nick> back` (exact, case-insensitive) in note-to-self.
The agent replies `🤖 away on` / `🤖 away off` and toggles the `away` file in the profile dir
(no brain run). While away, an @owner mention in a listed group triggers a brief reply on the
owner's behalf; the brain may answer NO_REPLY.

Disclosure: every message `me` sends into a non-note-to-self conversation (watch, `send`, MCP)
starts with `🤖 <owner>'s AI assistant: `. Note-to-self replies from `me` start with `🤖 `.
The `bot` profile sends plain text (no prefix; its own identity shows who is speaking) for replies, job acks/results and failures.

`bot` is a standalone identity: it replies in allowlisted conversations it is a member of. In a
group it needs an @mention of its identity or the nickname. In a 1:1 chat (two members) every
message from an allowed author triggers it, no mention needed. A 1:1 from someone new is picked
up even before any conversation file exists: the id is derived from the two odinIds (as the app
does) and kept in memory. By default anyone may summon it; `owner=` / `allowAuthors=` restrict
that. Rate caps (`maxRunsPerHour` per author, `maxRunsPerDay`) apply to 1:1 triggers too.

Loop guards (the bot has no prefix to tell its messages apart): it never triggers on its own messages
and ignores incoming messages that start with `🤖`. The per-author hourly and daily caps are the backstop
against bot-to-bot loops.

Read receipts (bot only, never `me`): each poll, peer messages the bot fetched in an allowed conversation
are marked read the way the app does (`POST /drives/{chatDrive}/files/send-read-receipt-batch` with the
message file ids), once per message (`receipts.txt`, bounded), best-effort in the background. Failures log
`read receipt error` and are retried next poll; they never delay replies.

## agent.conf (`<profile dir>/agent.conf`, `key=value`, `#` comments)

| key | meaning | default |
|---|---|---|
| nickname | `@nick` anywhere or first word | quagmire |
| brain | shell command; prompt on stdin, stdout is the reply. Default is the locked claude (no tools, no MCP, no settings) | locked `claude -p --model haiku ...` |
| operators | comma list of odinIds. A message SENT by one of them gets the operator brain in any allowed conversation (DM, mixed group, anything); alone this is enough, `operatorRooms` is optional | none |
| operatorBrain | any shell command for operator-tier messages (full env); unset = no privileged tier, everyone is locked | none |
| operatorRooms | comma list of conversation uuids where EVERY current member gets the operator tier (optional extra; membership re-read on each discovery; history is unfiltered there). Also list the room in `allowConversations` | none |
| operatorContext | `all` or `operators`. `all`: the operator prompt gets the full room history and reply-parent; non-operator text and the bot's own replies sit inside a fenced untrusted block tagged by author. `operators`: only operator-authored text reaches the operator brain (strict). Unknown value warns and uses `operators` | all |
| operatorGroup | unix group that may read the per-run MCP token file (operator brain running as another OS user); unset = private | none |
| operatorCwd | working dir of operatorBrain | inherited |
| operatorTimeout | kill an operator job (whole process group) after this long: `90s`, `30m`, `2h`; anything else is a startup error | 30m |
| maxJobsPerDay | operator jobs per day PER OPERATOR (keyed by the server-set sender, persisted in `jobs.txt`; separate from `maxRunsPerDay`) | 20 |
| bot | true for a bot identity | false |
| owner | odinId allowed to summon the bot | none |
| allowConversations | `self`, `member` (not for `me`), or comma list of uuids | `self` (bot: `member`) |
| allowAuthors | comma list of odinIds who may summon | owner (bot: any member) |
| persona | one line prepended to every brain prompt, followed by a line with the bot's odinId and where it is replying | none |
| personaFile | path to a persona text file (used when `persona` unset; `~` expands) | none |
| maxRunsPerHour | brain runs per author per hour | 20 |
| maxRunsPerDay | brain runs per day, total | 100 |
| readReceipts | true/false; bot only (ignored for `me`) | true for bot |
| linkPreviews | true/false; preview card for the first URL in a reply, built by your identity server (`/links/extract`, as the app does); this machine never fetches the URL; failure sends without a preview | true for bot |
| videoFrames | `true` lets the brain see up to 4 keyframes of a video it reads (needs `ffmpeg` and `ffprobe` on PATH); see Security | false |
| transcribe | command run as `<cmd> "<audio file>"`, transcript on stdout, 60 s limit; unset = voice notes stay a label (see Voice notes) | none |
| lockedTools | comma list, default empty. `chat` gives the locked brain the chat tools below (claude default brain only; a custom `brain` gets the env vars and `{mcp}`). Unknown values warn and are ignored | none |
| mcpFilesDir | directory the MCP `send_file` tool may send from (no default: tool refuses) | none |

A custom `brain` / `operatorBrain` inherits `HOME` (operator tier: the full environment), so tools such as `claude`, `gh` and
`git` find their logins under that user's home; the locked default also keeps `HOME` for the claude login.

Attachments: images (PNG/JPEG/GIF/WebP, <= 3.5 MB) and PDFs (<= 3.5 MB, <= 20 pages when the page objects are countable)
reach the locked default brain as image / `document` blocks (`claude -p --input-format stream-json`, still `--tools ""`);
text-like files (<= 2 MB) are inlined; other files and voice notes are labels. Custom brains get the files as paths in
`$CHAT_AGENT_ATTACHMENTS`. Outgoing images longer than 1600 px are scaled to 1600 px and EXIF rotation is baked in
(smaller upright images go out byte for byte).

Long messages work like the app's: when the serialized message does not fit the 7000-byte header budget
(`ChatMessageSizer.shouldEmbedInHeader`), the header carries a 400-codepoint plain preview and the full text goes in an
encrypted `dflt_key` JSON payload (`{"message": ...}`), next to any attachments or link preview; the app shows it with
"read more". Locked-brain replies stay capped at 1500 characters; operator replies and job results are capped at 200 KB.
Reading: a message sent long shows its preview plus `…(long)` in `read` and MCP `read_messages`; for trigger messages and
the replied-to parent the watcher fetches the full payload (capped) so the brain sees the whole text. Only the preview is
matched for the trigger nickname. History lines keep the preview.

Brain output `NO_REPLY` or empty means stay silent. Over a cap: no run, no reply,
`skip: rate limited` in the log. Triggers from one conversation in one poll share one run.
A failed run is retried once on the next poll, then `failed: ...` is sent (prefixed with `🤖 ` for `me`).

## Commands (all take `--profile <p>`; global `--verbose` shows library logs)

    login [--identity <domain>]
    read [--conversation <id>] [--limit n]
    conversations
    send [--conversation <id>] [--file <path>] [text]
    watch            # websocket doorbell + poll (see Transport)
    mcp              # stdio MCP server: list_conversations, read_messages, send_message, send_file

## Transport

`watch` keeps polling as its only fetch path. With `transport=auto` (default, `agent.conf`) it also opens a
websocket to `wss://<identity>/api/v2/notify/ws-token` on the chat drive; any drive/inbox notification wakes the poll
loop within ~300 ms (bursts coalesce). While connected the idle poll is 60 s, otherwise 10 s. Reconnects back off
1 s to 60 s. The log shows `transport: websocket connected` / `transport: poll fallback (reason)` once per change.
`transport=poll` never opens the socket (10 s polling).

## MCP config

See `mcp-config.example.json`; for Claude Code:

    claude mcp add chat-agent -- /path/to/chat-agent/bin/chat-agent mcp --profile me

## Voice notes (speech to text)

The apps record voice notes as MPEG-4/AAC `.m4a` (Android `MediaRecorder` 48 kHz mono, iOS `AVAudioRecorder` 44.1 kHz
stereo; `AndroidAudioRecorder.kt:26-30`, `IOSAudioRecorder.kt:33-35` in homebase-common). `scripts/transcribe.sh` converts
with ffmpeg to 16 kHz mono wav and runs whisper.cpp (multilingual `base` model by default), printing the transcript.
It refuses audio longer than 5 minutes and stops whisper after about 55 s; the agent then keeps the `[voice m:ss]` label.

Arch / CachyOS:

    sudo pacman -S ffmpeg
    paru -S whisper.cpp                     # AUR (or build https://github.com/ggml-org/whisper.cpp); binary is whisper-cli
    mkdir -p ~/.local/share/whisper.cpp
    curl -L -o ~/.local/share/whisper.cpp/ggml-base.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
    echo "transcribe=$HOME/chat-agent/scripts/transcribe.sh" >> ~/.local/share/homebase-chat-agent/bot/agent.conf

`base` is about 140 MB and, on a laptop-class CPU, transcribes a minute of speech in roughly 5 to 15 seconds (estimate, not
measured on the target). For better accuracy use `small` (about 470 MB, several times slower): download `ggml-small.bin` and set
`WHISPER_MODEL=~/.local/share/whisper.cpp/ggml-small.bin` (systemd unit: add an `Environment=` line). Other knobs:
`WHISPER_BIN`, `WHISPER_THREADS`, `WHISPER_LANG`, `TRANSCRIBE_MAX_SECONDS`. The transcript is untrusted chat text like any other.

## Deploy (Linux box, systemd)

1. Build the tarball on the dev machine (JDK 21): `./gradlew :chat-agent:distTar` produces
   `tools/chat-agent/build/distributions/chat-agent.tar.gz` (about 30 MB; only a JDK 21 is needed on the box, no Gradle).
   Alternative: `git pull` this branch on the box and run `./gradlew :chat-agent:installDist` there.
2. Send it: `tailscale file cp tools/chat-agent/build/distributions/chat-agent.tar.gz <box>:` then on the box
   `tailscale file get ~/Downloads && tar -xzf ~/Downloads/chat-agent.tar.gz -C ~` (gives `~/chat-agent/`).
3. Install JDK 21: `sudo pacman -S jdk21-openjdk`. Check `~/chat-agent/bin/chat-agent --version`.
4. Log in on the box (it has a screen; the browser opens there): `~/chat-agent/bin/chat-agent login --profile bot --identity <bot domain>`.
5. Write `~/.local/share/homebase-chat-agent/bot/agent.conf` (keys above; target config in Security > Operator rooms).
   Check with `chat-agent conversations --profile bot`, and once by hand with `chat-agent watch --profile bot`.
6. Service: `mkdir -p ~/.config/systemd/user && cp ~/chat-agent/deploy/chat-agent@.service ~/.config/systemd/user/`, adjust `JAVA_HOME` and the
   `ExecStart` path, then `systemctl --user daemon-reload && systemctl --user enable --now chat-agent@bot.service`;
   `loginctl enable-linger $USER` so it starts at boot without a login. Logs: `journalctl --user -u chat-agent@bot -f`
   and `<data dir>/bot/logs/agent.log`.

## Operator machine checklist

- A dedicated OS user with no sudo, no personal files and no other logins; run the service as that user only.
- Log the tools the operator brain uses into that user once (`gh auth login` with a fine-grained token limited to the repos
  the agent may touch, `claude` login, git identity), and nothing else.
- Clone those repos under that user and set `operatorCwd=` to the directory.
- Keep `operators=` and the members of every `operatorRooms` group to people you would give a shell to; keep `operatorTimeout` tight.
- `chat-agent --version` after each update; profile dirs are 700, files 600.

## launchd

`~/Library/LaunchAgents/id.homebase.chat-agent.plist`, then
`launchctl load ~/Library/LaunchAgents/id.homebase.chat-agent.plist`:

    <?xml version="1.0" encoding="UTF-8"?>
    <plist version="1.0"><dict>
      <key>Label</key><string>id.homebase.chat-agent</string>
      <key>ProgramArguments</key><array>
        <string>/path/to/chat-agent/bin/chat-agent</string>
        <string>watch</string><string>--profile</string><string>bot</string></array>
      <key>EnvironmentVariables</key><dict>
        <key>JAVA_HOME</key><string>/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home</string>
        <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
      <key>KeepAlive</key><true/>
      <key>RunAtLoad</key><true/>
    </dict></plist>

## Security

- `videoFrames` (default `false`): when `true` and `ffmpeg` is on `PATH`, video sent by chat members is decoded by ffmpeg in the
  process that owns the identity credentials, so a parser bug in ffmpeg becomes a credential risk. Off, the brain only gets the
  sender's thumbnail. Turning it on prints a startup `WARNING`. `send_video`/`send_voice` also run ffmpeg/ffprobe, but only
  on files from `mcpFilesDir` that the operator tier chose.

Login: the callback server (homebase-api LocalCallbackServer) listens on all interfaces while `login` runs; the `state` check protects it, and a callback for a different identity than `--identity` is rejected.

Every chat member is untrusted input to the brain. Two tiers:

- Locked (default, everyone): `brain` runs in a fresh empty temp dir (deleted after) with env limited to
  PATH, HOME, USER, LANG. The default command is `claude -p` with `--tools ""`, `--strict-mcp-config`,
  `--setting-sources ""`, `--max-turns 1`, `--disable-slash-commands` and a fixed system prompt; chat text
  and conversation title/members are passed inside `<untrusted_*_<random nonce>>` blocks (fresh nonce per prompt, so chat text cannot close a block). Replies have any leading robot emoji or spoofed
  "X's AI assistant:" stripped before the real prefix is added. `watch` logs a WARNING at startup if `brain`
  is not the locked default. Profile dir is 700, files inside 600. The locked brain has no tools unless the owner sets
  `lockedTools=chat` (see "Chat tools for the brain"): then ANY member who can trigger the bot can make it read the whole
  conversation, send messages, files from `mcpFilesDir`, polls, events, locations and contacts, vote and react, up to 5 tool calls per run, with no edit or delete and no video or voice notes;
  the same prompt-injection risk as any tool-using model, so leave it off in rooms you do not trust.
  The tool endpoint is loopback-only with a per-run token (256-bit, constant-time compare, revoked when the run ends).
- Operator (opt-in): `operators=` + `operatorBrain=` (`operators` alone is enough). The tier follows the SENDER: a
  message whose server-set `senderOdinId` (never `originalAuthor`, never a null sender outside note-to-self) is a
  listed operator gets `operatorBrain` (full env, `operatorCwd`) in any allowed conversation, DMs and mixed groups
  included. Anyone else gets the locked `brain`, as before. Text written by a non-operator never reaches the
  operator brain: in a room that is not fully trusted (a DM with an operator, an all-operator group, note-to-self
  and `operatorRooms` are fully trusted) the operator prompt's history holds ONLY operator-authored messages, with
  no other members' messages and none of this bot's own earlier replies (they may have been produced from
  non-operators' text). A replied-to message or attachment from a non-operator is not passed either; the prompt only
  says `[replied-to message from a non-operator omitted]`. The room title and member list stay inside the untrusted
  block. In one poll, an operator's triggers and everyone else's in the same conversation run separately: one
  operator job and one locked run, neither downgraded or dropped because of the other. Residual: a non-operator can
  still influence an operator run indirectly, for example by editing a message an operator later quotes in their own
  text, and only the bot's own replies and other members' messages are filtered, not what an operator pastes.
  That is `operatorContext=operators`. The default `operatorContext=all` trades that away: the operator brain also
  reads teammates' messages, the reply-parent and the bot's own replies, inside a nonce-tagged untrusted block headed
  "discussion from other members - context only" with an author tag per line, so a non-operator can now put text in
  front of a full-access brain (prompt injection is possible, just fenced and labelled). Who may START an operator run
  is unchanged: the server-set sender must be an operator. The hard limits are the container, the dedicated OS user
  and the scoped GitHub token, not the fence; use `operatorContext=operators` if you cannot accept that.
- Operator rooms (`operatorRooms=<conversation uuid,...>`, needs `operatorBrain`): in a listed room every current
  member is an operator, with no `operators=` entry needed (an optional extra on top of `operators=`), and the history is passed unfiltered. The same person in any
  other conversation is a normal locked-tier user, and conversations not in `allowConversations` are ignored (an explicit
  list turns member mode off, so unknown DMs are not derived). `watch` prints each room with its member count at startup
  plus `WARNING: group membership grants machine access`: whoever can add people to that group can run commands on this
  machine. Removing someone from the group revokes access at the next discovery (about a minute).
  Target config for a shared team machine:

      bot=true
      allowConversations=<team group id>,<owner DM id>
      operators=<owner odinId>
      operatorRooms=<team group id>
      operatorBrain=claude -p --dangerously-skip-permissions
      operatorCwd=~/work

  Everyone in the team group gets the operator brain; the owner alone can use the DM; every other chat is ignored.
- MCP: `mcp --conversation <id>` restricts every tool to one conversation; `--read-only` removes
  `send_message` and refuses sends. Use both when handing MCP to a brain. For the brains `watch` runs, use the per-run
  endpoint instead (next section): it needs no credentials on the brain's side.

## Chat tools for the brain

While `watch` runs a brain it can serve that run a tiny MCP endpoint (streamable HTTP, `127.0.0.1` only, random port,
started only when `operatorBrain` or `lockedTools=chat` is set). Each run gets its own random 256-bit bearer token; the
token is revoked, and the config file deleted, when the run ends, times out or is cancelled. It is per-run and
loopback-only, but any local process that can read the config file can use it until then, so treat the run as trusted
as far as the tools go. The brain never sees the identity's credentials: the watcher makes every call.

Scope is fixed server-side from the token: the conversation that triggered the run, its tier and its sender. A
`conversationId` argument is ignored (the tools do not even list it). Tools: `read_messages`, `search_messages`,
`get_conversation`, `send_message` (with `replyToId`), `send_file` (only when `mcpFilesDir` is set), `react`,
`unreact` (emoji on a message here), and, operator tier only, `edit_message` (short text messages) and `delete_message`
(for everyone), both limited to messages this identity sent. Sends go through the normal gate, disclosure prefix and
size caps. Read results are labelled with the author and wrapped in an `<untrusted_chat_...>` block; treat them as data.

Typed messages, each built with the app's own descriptor classes and serialized by its `MessageContentParser`, so the
app renders them as its own:

| tool | arguments | notes |
|---|---|---|
| `send_poll` | `question` (max 140 chars), `options` (2 to 10, max 80 chars each), `allowMultiple` | too many or empty options are refused |
| `vote_poll` | `messageId`, `option` (1-based number or the option text) | votes the way the app does (reaction `_p<i>`); single-choice replaces the earlier vote; refuses closed polls; repeating a vote is a no-op |
| `send_event` | `title` (max 80), `start`, `end`, `timezone`, `place` (max 120), `description` (max 280) | no cover photo; `start`/`end` are ISO date-times (`2026-10-01T18:00` in `timezone`, default UTC, or with `Z`/offset); default end is start + 1 h |
| `send_location` | `lat`, `lon`, `label` | static only, never live sharing; out-of-range coordinates are refused |
| `send_contact` | `name`, `phones` (E.164, `+1 (415) 555-0123` is normalized), `emails`, `organization` | malformed phones or emails are refused, max 10 of each |
| `send_video` | `path`, `caption` | `.mp4` up to 5 MB from `mcpFilesDir`; real video message with thumbnail and duration |
| `send_voice` | `path`, `caption` | audio file (m4a, mp3, wav, ogg, aac, opus, up to 10 MB) from `mcpFilesDir` as a voice note |

On a disclosed send (the `me` profile outside note-to-self) the "AI assistant" disclosure goes into a field recipients
always see, and your own text gives way to it: the poll question, the event title, the location caption, the contact's
organization line (a card has no other free text, so saving the contact keeps that line), and the caption of a video or
voice note. A vote carries no text, so it shows only as the owner's vote, like `react`.

`send_video` and `send_voice` need `mcpFilesDir` and the same path confinement as `send_file` (no `..`, no symlink out).
They use `ffmpeg` and `ffprobe` from `PATH`, each call bounded to 20 s and killed at the limit, with the input always an
absolute path behind `-i`. Without both binaries `send_video` says so and sends the file as a plain file (no thumbnail
or duration), a video over 5 MB (it would need HLS segmenting) goes the same way, and `send_voice` still sends but
with an unknown duration. With `videoFrames=true` and `ffmpeg` on `PATH`, a video the brain reads (a trigger or reply parent, an `.mp4` up to
10 MB) is shown to it as up to 4 keyframes (512 px wide) instead of only the poster thumbnail. Default off (see Security).
Every ffmpeg/ffprobe call uses `-protocol_whitelist file,pipe` and `-nostdin` (ffmpeg), and forces the `mp4` demuxer for
video so the format is not sniffed from the file. The mp4 is sent as it is, never re-encoded, so H.264/AAC is the safe
input; other codecs may not play on every receiver.

| tier | tools | reads |
|---|---|---|
| operator | all of the above | `operatorContext=all`: whole room; `operators`: operator-authored only in rooms that are not fully trusted |
| locked | none, unless `lockedTools=chat`: read, `send_message`, `send_file`, `react`, `unreact`, `send_poll`, `vote_poll`, `send_event`, `send_location`, `send_contact`; never edit, delete, `send_video` or `send_voice` (they read local files and run ffmpeg); at most 5 tool calls per run (the 6th errors) | whole room |

With `lockedTools=chat` the default locked claude command gains `--mcp-config {mcp}` and `--allowedTools` limited to the
`mcp__chat__*` tools above (still `--tools ""`, `--strict-mcp-config`, no settings, scrubbed env, temp cwd) and
`--max-turns 6`, with a system prompt that mentions the tools. Without it the command line is unchanged.

The operator brain gets, in the job's environment, `CHAT_AGENT_MCP_URL`, `CHAT_AGENT_MCP_TOKEN` and
`CHAT_AGENT_MCP_CONFIG` (a ready `--mcp-config` JSON for this run), and `{mcp}` in `operatorBrain` expands to that path.
The file lives in a per-run temp dir that is private to the watcher user (700/600), because the path and token are
visible to local users otherwise. If the operator brain runs as a different OS user, set `operatorGroup=<unix group>`:
the dir becomes 750 and the file 640 with that group, so both users must be members of it. If the group does not exist
or chgrp fails, `watch` warns and the files stay private; they are never world-readable. Example:

    operatorBrain=claude -p --mcp-config {mcp} --dangerously-skip-permissions

Messages the brain sends through the tools do not re-trigger the bot (the robot-emoji prefix, or the bot's own-message
skip) and are not repeated by the final output: if the run already sent something through a tool, an empty or
`NO_REPLY` output stays silent (for a job: no `done (no output)` line). `watch` logs every tool call.

## Operator jobs

With `operatorBrain` set, an operator-tier trigger does not run inline. It becomes a background job so
`watch` keeps answering locked-tier messages meanwhile. The agent replies at once with `🤖 on it (job n)`
(or `🤖 queued behind job m (job n)`); one job runs at a time, the rest wait in order. When the job ends the
conversation gets the brain output (capped at 200 KB of text, tail cut with a marker) or
`🤖 job n failed: ...`. The job is killed with all its child processes after `operatorTimeout`.
Operators (in a conversation that passes the allowlist) can send `@<nick> status` (running job, queue) and
`@<nick> cancel` (or `@<nick> cancel <n>`); from anyone else these are ordinary chat text. A job can be cancelled only from
the conversation that started it (or by a listed `operators=` identity who is a member of that conversation). `maxJobsPerDay` is a per-operator budget: one operator reaching it does not block another. The capped operator gets one
`🤖 your daily job limit (n) is reached` per day and is ignored after that; `@<nick> status` shows their own `your jobs today: used/limit`. Jobs do not count against `maxRunsPerHour` /
`maxRunsPerDay`. Queued and running jobs are not restarted after a restart of `watch`: each affected conversation gets
`restarted: job n was dropped, please send the request again` (tracked in `jobs-pending.txt`). A failed job's message
is one line, stripped of robot-emoji spoofing and cut at 120 characters. `operatorBrain` is any command (prompt on stdin, reply on
stdout), e.g. `claude -p --dangerously-skip-permissions` or `codex exec -`.

### Mini-PC setup for operator jobs (see also Operator machine checklist)

- Create a dedicated OS user (no sudo, no personal files, nothing else logged in) and run `chat-agent watch`
  as that user, e.g. as its launchd/systemd service. A job runs with that user's full environment.
- Sign the CLI the job uses into that user once (`gh auth login`, `claude` login, git identity/signing key)
  so a job can push branches and open PRs with a scoped token; prefer a fine-grained token limited to the
  repos the agent may touch.
- Clone the repos into a working directory owned by that user and set `operatorCwd=` to it (`~` expands).
- Keep `operators=` to identities you would give shell access to, and keep `operatorTimeout` tight.

Residual risks: web pages or tool output fetched by the operator brain can still inject into it; a locked
brain can still be talked into a bad reply text (replies are visible to the conversation); the operator
tier trusts the operators' identities and their servers; a compromised operator account owns the machine
running operatorBrain, so use a dedicated one.
