# AgentUi: agent-composed interactive cards

A typed message kind (dataType 218) where an agent composes a card from a fixed set of native
blocks, and people act on it (approve, pick, fill in) without leaving the chat. Actions come back
as typed messages (dataType 219). Follows `ADDING_TYPED_MESSAGE_KIND.md`; it is the third
payload-bearing kind, and has no composer or attachment entry (only agents send it).

## A2UI decision

Evaluated Google's A2UI spec v0.8 (the page marks it legacy; v0.9.1 is current, v1.0 a candidate).
We adopt a subset of its shape, not its transport or styling.

Taken: the flat component list with string ids and a root id; the component names where they
overlap (Column, Row, Card, Text, Image, Divider, List, Button, CheckBox as `toggle`, TextField as
`textInput`, MultipleChoice as `select`); the `userAction` idea (action name plus context values).

Dropped: the JSONL stream (`surfaceUpdate`, `beginRendering`, `deleteSurface`; an edit replaces the
whole tree); data-model binding and JSON-pointer paths (inputs hold local state and are read when
an action fires); URL images (an image names one of this message's own payloads); Video, Audio,
Modal, Tabs, Slider, DateTime; every style or size prop.

Added: `chip`, `table`, `progress`. A carousel is `list` with `direction: "horizontal"`.

Re-check against A2UI when bumping `schemaVersion`.

## Wire format

### 1. Header (`appData.content`, dataType 218): `AgentUiDescriptor`

```json
{"schemaVersion":1,"fallback":"Draft post for X and LinkedIn","title":"Draft post","rev":0}
```

- `fallback`: required, non-blank, at most 1000 code points. Shown while the tree loads, if it
  fails, and copied by "Copy".
- `title`: optional, at most 80 code points (conversation list, pins).
- `rev`: integer, 0 or more. The agent bumps it on every edit.
- No id, author or timestamp: the envelope has them. An invalid header renders the "please update"
  chip (`AgentUi(null)`); the message is never dropped. `schemaVersion` other than 1 is invalid.
- Push notification text is the generic "Agent card", never the content.

### 2. Tree payload

Payload key `agent_ui` (`ChatProtocol.PAYLOAD_KEY_AGENT_UI`; the server requires `^[a-z0-9_]{8,10}$`),
contentType `application/json`, encrypted with the message key.

```json
{"root":"id","nodes":[{"id":"id","type":"column","fallback":"optional plain text", "...":"props"}]}
```

Every node may carry `fallback`. An unknown `type` renders its `fallback` (nothing if absent).

### 3. Images

Separate payloads `chat_web0` to `chat_web9`. `{ "type":"image", "payloadKey":"chat_web0", "alt":"..." }`.
`alt` is required. A key that is not an image payload of this message, or a missing `alt`, renders
the alt/fallback text. Remote URLs are not a thing.

### 4. Node catalog

| type | props |
|---|---|
| `column`, `row` | `children` (ids), `spacing`: none, small, medium |
| `card` | `children`, `tone`: default, primary, secondary, error |
| `text` | `markdown` (chat markdown subset, links open on tap), `style`: title, body, label, caption; `tone`: default, primary, secondary, error |
| `image` | `payloadKey`, `alt` |
| `chip` | `label`, `selected?` (display only) |
| `divider` | |
| `list` | `children`, `direction`: vertical, horizontal |
| `table` | `header` (strings), `rows` (arrays of strings) |
| `progress` | `value` 0..1 or null (indeterminate), `label?` |
| `button` | `label`, `style`: filled, tonal, outlined, text, destructive; `action` |
| `toggle` | `label`, `value`, `action?` (fires on change) |
| `textInput` | `label`, `value`, `multiline`, `maxLength`, `action?` (single-line only: fires on the keyboard's send key) |
| `select` | `label`, `options` `[{id,label}]`, `value?`, `action?` (fires on pick) |

`action` = `{ "id": "[a-z0-9_.-]{1,64}", "include": [nodeIds] }`. An invalid id drops the action
(the button is disabled). The acting node's own value is always sent; `include` adds other input
nodes. Ids that are not inputs are ignored.

Enums outside the list fall back to the first listed value (`body`, `default`, `small`, `filled`,
`vertical`). Wrong JSON types read as absent.

### 5. Limits

| Limit | Value | Over the limit |
|---|---|---|
| payload bytes (checked before parsing) | 64 KB | tree invalid |
| JSON nesting | 8 | tree invalid |
| nodes in the list | 200 | tree invalid |
| tree depth | 10 | tree invalid |
| children per node | 50 | tree invalid |
| images | 10 | tree invalid |
| each ref'd id used once (no cycle, no diamond), ids unique, root present | | tree invalid |
| text / fallback | 2000 code points | clamped |
| labels, table cells, alt | 200 code points | clamped |
| table | 20 rows x 8 columns | clamped |
| options | 50 | clamped |
| `include` | 20 ids | clamped |
| input value | 1000 code points (`maxLength` 1 to 1000) | clamped |

An invalid tree shows the header `fallback`. Missing child ids are dropped. `AgentUiTree.decode`
is pure, common and never throws, so the agent can use it to validate before sending.

### 6. Action reply (dataType 219): `AgentUiActionDescriptor`

```json
{"schemaVersion":1,"target":"<AgentUi message uniqueId>","rev":2,"actionId":"approve","label":"Approve","values":{"caption":"..."}}
```

- `values`: node id to string. Toggles `"true"`/`"false"`, selects the option id, inputs the text.
  At most 20 entries, each at most 1000 code points; the whole descriptor at most 4096 bytes (an
  over-size action is not sent and the card shows an error).
- `target` points at another message. Who acted is the server-set sender of this message
  (`originalAuthor`), never a field here. The agent identity itself cannot act.
- Push: none (`pushesToRecipient = false`). Older apps show the Unknown chip for each action.

### 7. Edit semantics

The agent re-uploads the `agent_ui` payload and bumps `rev` in the header (an update). Receivers
re-fetch when the payload's `lastModified` or iv changes; the fetch is cache-addressed by
`lastModified`. Typed text survives an edit unless that input's default changed.

An action whose `rev` is older than the card's is stale: the agent must reject it. After a terminal
action (for example Approve posts something) the agent must edit the card to remove the buttons.
The app disables an action after one send per `rev`, and re-enables it if the send fails.

### 8. Reference: draft-post card

See `AgentUiFixtures.draftPostCardJson()` in `homebase-chat/src/commonTest/.../agentui/`:

```json
{"root":"card","nodes":[
  {"id":"card","type":"card","children":["head","pics","caption","chips","actions"]},
  {"id":"head","type":"text","style":"title","markdown":"Draft post"},
  {"id":"pics","type":"list","direction":"horizontal","children":["img0","img1"]},
  {"id":"img0","type":"image","payloadKey":"chat_web0","alt":"Slide one: the spring launch"},
  {"id":"img1","type":"image","payloadKey":"chat_web1","alt":"Slide two: the team"},
  {"id":"caption","type":"text","style":"body","markdown":"Spring is here. **Meet** the new release."},
  {"id":"chips","type":"row","spacing":"small","children":["ch_x","ch_li","ch_ig"]},
  {"id":"ch_x","type":"chip","label":"X","selected":true},
  {"id":"ch_li","type":"chip","label":"LinkedIn","selected":true},
  {"id":"ch_ig","type":"chip","label":"Instagram"},
  {"id":"actions","type":"row","spacing":"small","children":["approve","edit","discard"]},
  {"id":"approve","type":"button","label":"Approve","style":"filled","action":{"id":"approve"}},
  {"id":"edit","type":"button","label":"Edit","style":"tonal","action":{"id":"edit"}},
  {"id":"discard","type":"button","label":"Discard","style":"destructive","action":{"id":"discard"}}
]}
```

## Security

No webview, no scripts, no remote URLs (a remote image would leak the reader's IP). Markdown images
render as link text. The tree is untrusted: validated, clamped, and a property test
(`AgentUiTreePropertyTest`) feeds random and oversized trees. Agent text is content, never app copy;
BiDi and zero-width characters are clamped by length only and render as content.

## Known gaps

- Text input in the message list: verify the IME does not cover the field on Android, and focus on web.
- Pending-action state is per composition: scrolling the card away and back re-enables a sent action
  until the agent edits the card (the agent rejects stale/duplicate by `rev`).
