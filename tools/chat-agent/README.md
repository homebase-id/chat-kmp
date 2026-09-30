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
| operators | comma list of odinIds trusted for the operator tier | none |
| operatorBrain | any shell command for operator rooms (full env); unset = no privileged tier | none |
| operatorRooms | comma list of conversation uuids where EVERY current member gets the operator tier (membership re-read on each discovery; history is unfiltered there). Also list the room in `allowConversations` | none |
| operatorCwd | working dir of operatorBrain | inherited |
| operatorTimeout | kill an operator job (whole process group) after this long: `90s`, `30m`, `2h`; anything else is a startup error | 30m |
| maxJobsPerDay | operator jobs per day (separate from `maxRunsPerDay`) | 20 |
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
| transcribe | command run as `<cmd> "<audio file>"`, transcript on stdout, 60 s limit; unset = voice notes stay a label (see Voice notes) | none |
| mcpFilesDir | directory the MCP `send_file` tool may send from (no default: tool refuses) | none |

A custom `brain` / `operatorBrain` inherits `HOME` (operator tier: the full environment), so tools such as `claude`, `gh` and
`git` find their logins under that user's home; the locked default also keeps `HOME` for the claude login.

Attachments: images (PNG/JPEG/GIF/WebP, <= 3.5 MB) and PDFs (<= 3.5 MB, <= 20 pages when the page objects are countable)
reach the locked default brain as image / `document` blocks (`claude -p --input-format stream-json`, still `--tools ""`);
text-like files (<= 2 MB) are inlined; other files and voice notes are labels. Custom brains get the files as paths in
`$CHAT_AGENT_ATTACHMENTS`. Outgoing images longer than 1600 px are scaled to 1600 px and EXIF rotation is baked in
(smaller upright images go out byte for byte).

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

Login: the callback server (homebase-api LocalCallbackServer) listens on all interfaces while `login` runs; the `state` check protects it, and a callback for a different identity than `--identity` is rejected.

Every chat member is untrusted input to the brain. Two tiers:

- Locked (default, everyone): `brain` runs in a fresh empty temp dir (deleted after) with env limited to
  PATH, HOME, USER, LANG. The default command is `claude -p` with `--tools ""`, `--strict-mcp-config`,
  `--setting-sources ""`, `--max-turns 1`, `--disable-slash-commands` and a fixed system prompt; chat text
  and conversation title/members are passed inside `<untrusted_*_<random nonce>>` blocks (fresh nonce per prompt, so chat text cannot close a block). Replies have any leading robot emoji or spoofed
  "X's AI assistant:" stripped before the real prefix is added. `watch` logs a WARNING at startup if `brain`
  is not the locked default. Profile dir is 700, files inside 600.
- Operator (opt-in): `operators=` + `operatorBrain=`. `operatorBrain` runs in `operatorCwd` with the full
  environment, only when every trigger author is an operator (or this identity) AND every other member of the
  conversation is an operator (a DM, an all-operator group, or note-to-self). One non-operator member, or a
  non-operator trigger coalesced into the same run, keeps the whole run locked. History given to the operator
  brain contains only operator/own messages. Identity is the server-set `senderOdinId`, never `originalAuthor`.
- Operator rooms (`operatorRooms=<conversation uuid,...>`, needs `operatorBrain`): in a listed room every current
  member is an operator, with no `operators=` entry needed, and the history is passed unfiltered. The same person in any
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
  `send_message` and refuses sends. Use both when handing MCP to a brain.

## Operator jobs

With `operatorBrain` set, an operator-tier trigger does not run inline. It becomes a background job so
`watch` keeps answering locked-tier messages meanwhile. The agent replies at once with `🤖 on it (job n)`
(or `🤖 queued behind job m (job n)`); one job runs at a time, the rest wait in order. When the job ends the
conversation gets the brain output (truncated to 1500 characters, keeping the END) or
`🤖 job n failed: ...`. The job is killed with all its child processes after `operatorTimeout`.
Operators (in a conversation that passes the allowlist) can send `@<nick> status` (running job, queue) and
`@<nick> cancel` (or `@<nick> cancel <n>`); from anyone else these are ordinary chat text. A job can be cancelled only from
the conversation that started it (or by a listed `operators=` identity who is a member of that conversation). Over
`maxJobsPerDay` the agent answers `🤖 daily job limit reached`. Jobs do not count against `maxRunsPerHour` /
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
