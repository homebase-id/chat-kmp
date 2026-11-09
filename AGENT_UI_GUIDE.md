# Writing AgentUi cards: a guide for agent authors

How to compose a card that looks like part of the app. The wire format, validation and edit rules
are in [`AGENT_UI.md`](AGENT_UI.md); this page is about choosing nodes and staying inside the limits.
Every number below comes from `AgentUiLimits`, `AgentUiDescriptor`, `AgentUiActionDescriptor`,
`Dimens.AgentUi` and `AgentUiRenderer.kt`.

## What the reader sees

- The card is at most **360 dp wide** (520 dp on windows 600 dp and wider), with 16 dp padding and
  a 24 dp corner. Plan for a phone: about 330 dp of content width.
- A root `card` does not draw a second box: its `tone` becomes the bubble colour. Nest a `card` only
  to set a block apart.
- Colours and type come from the reader's Material 3 theme. You pick a role (`tone`, `style`), never
  a colour or size. Light and dark mode both work without you doing anything.
- Until the tree is ready, and whenever it is invalid, the reader sees `fallback`. Write it as a
  complete one-line summary ("Draft post for X and LinkedIn, waiting for approval"), not "Loading".

## Nodes and when to use them

| Node | Use it for | Avoid |
|---|---|---|
| `card` | The root, or one highlighted block inside it. `tone: error` adds an error icon and is for failures only. | Cards inside cards inside cards. One level of nesting is enough. |
| `column` | Stacking blocks. `spacing` small (8 dp) is the default; medium (16 dp) separates sections. | A column with one child. |
| `row` | A few short items side by side: chips, buttons. It wraps onto new lines when it runs out of width. | Long text in a row; it wraps unpredictably. |
| `text` | All prose. `title` once at the top, `body` for content, `label` for small headings, `caption` for metadata. Chat markdown works (bold, italic, links, lists). | Several `title`s. Markdown images (they render as link text). Tones for decoration: `primary` and `error` mean something. |
| `image` | A picture the person should look at. Always 16:9, cropped to fill. Tapping it opens the app's full-screen viewer, which pages through every image on the card. | Text baked into an image (unreadable cropped, invisible to screen readers). Portrait images (cropped hard). |
| `list` | `vertical`: repeated items. `horizontal`: a carousel of 260 dp items, usually images. | A horizontal list of text; it scrolls sideways and hides content. |
| `chip` | Display-only tags ("X", "LinkedIn"); `selected` marks the ones that apply. | Chips as buttons. They take no taps; use `select` for a choice. |
| `divider` | Splitting two sections that spacing alone does not separate. | A divider between every block. |
| `table` | Small tabular data. A column whose cells all look numeric is right-aligned and narrower. | Wide tables: 8 columns is the cap, but 3 or 4 is what fits on a phone. |
| `progress` | A running job. `value` 0..1, or null while you cannot say. | Progress you never update. Edit the card (bump `rev`) as the job moves. |
| `button` | Actions. One `filled` primary action, `tonal` or `outlined` secondaries, `destructive` for discard or delete, `text` for minor ones. | Two `filled` buttons. More than three buttons in a row. |
| `toggle` | An on/off setting, sent with a button (`include`) or on its own (`action`). | A toggle with an `action` for something the person will flip twice: it can be sent once per `rev`. |
| `textInput` | Short free text (`multiline` for a few lines). Set `maxLength` to what you will accept. | Long documents. A single-line input with an `action` sends on the keyboard's send key. |
| `select` | One choice out of a few options, shown as chips. With an `action` the pick is sent at once; picking the current option again sends nothing. | Dozens of options (50 is the cap; ten is already a lot of chips). |

Unknown node types render their `fallback` (or nothing). Give every node you add from a newer
schema a `fallback`.

## Actions

- `action` = `{ "id": "approve", "include": ["caption", "notify"] }`. The acting node's own value is
  always sent; `include` adds other inputs (at most 19, so 20 values in all).
- An action is sent once per `rev`. The control shows a spinner and stays disabled until you edit
  the card. If the send fails, the app re-enables it and shows "Couldn't send that action".
- After a terminal action (Approve posts something), edit the card to remove or disable the
  buttons, and reject actions whose `rev` is older than the card's.
- The reply holds at most 2796 bytes of serialized values. Do not prefill inputs so that an action
  is already over that budget: `AgentUiTree.overBudgetActions()` lists such actions; refuse the tree.

## Limits

| What | Limit | Over it |
|---|---|---|
| Inline tree (whole descriptor in the header) | 5247 bytes of descriptor JSON (7000 encrypted) | send the tree as the `agent_ui` payload instead (`AgentUiDescriptor.withInlineTree` decides) |
| Payload tree | 64 KB | tree invalid, fallback shown |
| Nodes | 200 | tree invalid |
| Depth | 10 levels | tree invalid |
| Children per node | 50 | tree invalid |
| JSON nesting | 8 | tree invalid |
| Images | 10 (`chat_web0` to `chat_web9`) | tree invalid |
| `fallback` | 1000 code points, non-blank | header invalid: the reader sees the "please update the app" chip |
| `title` | 80 code points | header invalid |
| Text, node `fallback` | 2000 code points | clamped |
| Labels, alt, table cells | 200 code points | clamped |
| Table | 20 rows x 8 columns | clamped |
| Select options | 50 | clamped |
| Input value, `maxLength` | 1000 code points | clamped |

Inline is better: the card renders on the first frame with no fetch. A short card with a title,
a paragraph, a few chips and three buttons fits inline with room to spare; long reports and big
tables go in the payload.

### Images

- Upload each image as a separate payload `chat_web0`..`chat_web9` on the same message, with an
  embedded preview and the standard thumbnails. The card shows the preview at once, then the
  640 px (`THUMB_MEDIUM`) thumbnail; the full-screen viewer loads the full image.
- Send landscape images close to 16:9 and at least 1280 px wide, so the thumbnail is sharp and the
  crop keeps the subject.
- `alt` is required and is what screen readers read. Describe the picture ("Slide one: the spring
  launch"), not the file ("image1.png"). A missing `alt` or a key that is not an image payload of
  this message shows the alt or fallback text instead.

## A good card

A draft post waiting for approval: one title, the images in a carousel, the text, the target
networks, and one clear primary action.

```json
{"root":"card","nodes":[
  {"id":"card","type":"card","children":["head","pics","caption","chips","actions"]},
  {"id":"head","type":"text","style":"title","markdown":"Draft post"},
  {"id":"pics","type":"list","direction":"horizontal","children":["img0","img1"]},
  {"id":"img0","type":"image","payloadKey":"chat_web0","alt":"Slide one: the spring launch"},
  {"id":"img1","type":"image","payloadKey":"chat_web1","alt":"Slide two: the team"},
  {"id":"caption","type":"text","markdown":"Spring is here. **Meet** the new release."},
  {"id":"chips","type":"row","children":["ch_x","ch_li"]},
  {"id":"ch_x","type":"chip","label":"X","selected":true},
  {"id":"ch_li","type":"chip","label":"LinkedIn","selected":true},
  {"id":"actions","type":"row","children":["approve","edit","discard"]},
  {"id":"approve","type":"button","label":"Approve","style":"filled","action":{"id":"approve"}},
  {"id":"edit","type":"button","label":"Edit","style":"tonal","action":{"id":"edit"}},
  {"id":"discard","type":"button","label":"Discard","style":"destructive","action":{"id":"discard"}}
]}
```

Fallback: `"Draft post for X and LinkedIn, waiting for your approval"`.

## A bad card

```json
{"root":"c","nodes":[
  {"id":"c","type":"column","children":["t1","t2","img","inner","b1","b2","b3","b4"]},
  {"id":"t1","type":"text","style":"title","markdown":"POST READY!!!"},
  {"id":"t2","type":"text","style":"title","tone":"error","markdown":"Please review"},
  {"id":"img","type":"image","payloadKey":"chat_web0","alt":"image"},
  {"id":"inner","type":"card","tone":"primary","children":["inner2"]},
  {"id":"inner2","type":"card","children":["body"]},
  {"id":"body","type":"text","markdown":"![logo](https://example.com/logo.png) Here is the post text..."},
  {"id":"b1","type":"button","label":"Approve","style":"filled","action":{"id":"approve"}},
  {"id":"b2","type":"button","label":"Approve and schedule","style":"filled","action":{"id":"schedule"}},
  {"id":"b3","type":"button","label":"Edit"},
  {"id":"b4","type":"chip","label":"Discard"}
]}
```

What is wrong with it:

- The root is a `column`, so the card has no tone of its own, and two `title`s compete; the
  `error` tone says something failed when nothing did.
- `alt: "image"` tells a screen-reader user nothing.
- A card in a card in a card for one paragraph. The remote image in the markdown never loads.
- Four buttons stacked one per line, two of them `filled`. "Edit" has no `action`, so it is
  disabled. "Discard" is a chip, which takes no taps.
- No `fallback` worth reading if the tree fails.
