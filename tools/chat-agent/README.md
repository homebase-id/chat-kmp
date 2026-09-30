# chat-agent

Headless Homebase Chat agent: reads/sends messages and replies when its profile is
summoned (nickname, or its own identity for a bot). Runs a "brain" command per trigger.

## Build

    export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # JDK 21 required
    ./gradlew :chat-agent:installDist
    tools/chat-agent/build/install/chat-agent/bin/chat-agent <command> ...

Data lives in `~/Library/Application Support/HomebaseChatAgent/<profile>/`
(credentials, `agent.conf`, `processed.txt`, `runs.txt`, `logs/agent.log`).

## Login

    chat-agent login --profile me  --identity you.homebase.id    # delegate: acts as the owner
    chat-agent login --profile bot --identity bot.homebase.id    # bot: its own identity

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
| operatorCwd | working dir of operatorBrain | inherited |
| operatorTimeout | kill an operator job (whole process tree) after this long: `90s`, `30m`, `2h` | 30m |
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

Brain output `NO_REPLY` or empty means stay silent. Over a cap: no run, no reply,
`skip: rate limited` in the log. Triggers from one conversation in one poll share one run.
A failed run is retried once on the next poll, then `failed: ...` is sent (prefixed with `🤖 ` for `me`).

## Commands (all take `--profile <p>`; global `--verbose` shows library logs)

    login [--identity <domain>]
    read [--conversation <id>] [--limit n]
    conversations
    send [--conversation <id>] <text>
    watch            # polls every 10s
    mcp              # stdio MCP server: list_conversations, read_messages, send_message

## MCP config

See `mcp-config.example.json`; for Claude Code:

    claude mcp add chat-agent -- /path/to/chat-agent/bin/chat-agent mcp --profile me

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
- MCP: `mcp --conversation <id>` restricts every tool to one conversation; `--read-only` removes
  `send_message` and refuses sends. Use both when handing MCP to a brain.

## Operator jobs

With `operatorBrain` set, an operator-tier trigger does not run inline. It becomes a background job so
`watch` keeps answering locked-tier messages meanwhile. The agent replies at once with `🤖 on it (job n)`
(or `🤖 queued behind job m (job n)`); one job runs at a time, the rest wait in order. When the job ends the
conversation gets the brain output (truncated to 1500 characters, keeping the END) or
`🤖 job n failed: ...`. The job is killed with all its child processes after `operatorTimeout`.
Operators (in a conversation that passes the allowlist) can send `@<nick> status` (running job, queue) and
`@<nick> cancel` (or `@<nick> cancel <n>`); from anyone else these are ordinary chat text. Over
`maxJobsPerDay` the agent answers `🤖 daily job limit reached`. Jobs do not count against `maxRunsPerHour` /
`maxRunsPerDay`. Queued jobs are lost on restart. `operatorBrain` is any command (prompt on stdin, reply on
stdout), e.g. `claude -p --dangerously-skip-permissions` or `codex exec -`.

### Mini-PC setup for operator jobs

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
