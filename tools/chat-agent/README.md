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

`me` only triggers on the nickname and only ever sends to note-to-self.
`bot` may also be summoned by an @mention of its identity and replies in
allowlisted conversations it is a member of.

## agent.conf (`<profile dir>/agent.conf`, `key=value`, `#` comments)

| key | meaning | default |
|---|---|---|
| nickname | `@nick` anywhere or first word | quagmire |
| brain | shell command; prompt on stdin, stdout is the reply | `claude -p --model haiku --max-turns 3` |
| bot | true for a bot identity | false |
| owner | odinId allowed to summon the bot | none |
| allowConversations | `self`, `member`, or comma list of uuids | `self` (bot: `member`) |
| allowAuthors | comma list of odinIds who may summon | owner (bot: any member) |
| maxRunsPerHour | brain runs per author per hour | 20 |
| maxRunsPerDay | brain runs per day, total | 100 |

Brain output `NO_REPLY` or empty means stay silent. Over a cap: no run, no reply,
`skip: rate limited` in the log. Triggers from one conversation in one poll share one run.
A failed run is retried once on the next poll, then `🤖 failed: ...` is sent.

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
