# Chat message formatting

Chat bodies may carry a small inline markup: bold, italic, underline, strikethrough, bulleted and numbered
lists. This is the one definition of that grammar. It is implemented three times on purpose, in the language
of each surface, the way the URL rule already is:

| Surface | Parser | Renderer(s) |
|---|---|---|
| Server (mails, push preview, reply snippet) | `model/chat/ChatMarkup.java` | `chat/ChatBodyHtml.java` (mail HTML through an allowlist), `ChatMarkup.plain` (push alert body, `ChatQuote.from`) |
| Website | `medjugorje/webapp/resources/trip-js/chatFormat.js` (`TripChatFormat`) | `render()` used by `trip/chat.xhtml` (the pane) and `photoViewer.js` (photo comments); the composer strip, shortcuts and Enter continuation on `chat.xhtml` |
| UniteTrip iOS | `Features/Chat/Models/ChatMarkup.swift` | `ChatRowsBuilder.blocks` -> `ChatBodyView`/`MentionText`; `ChatMarkupEditing` behind the keyboard toolbar |

Each implementation carries the SAME conformance table (`ChatMarkupTest.cases`, `ChatFormattingPwIT.CASES`,
`ChatMarkupTests.cases`) in a shared debug notation. Change a case in one and change all three, or one
surface reads a body differently from the rest.

## Why markers, not HTML

Bodies are stored exactly as typed (`chat-design.md`, "escape on output, not on input") and rendered by
client JS from JSON on the website, so the design doc ruled: never accept HTML and sanitize it; accept a
restricted markup and render it through an allowlist that emits only known-safe elements. That is what this
is. No renderer ever copies body text into markup: the website creates `b i u s ul ol li` elements and
hands every text run to the existing mention/link appender (text nodes only), the mails escape every run
through `ChatLinks.html`, and the app builds an `AttributedString`.

The composer stays a plain textarea (web) / `TextField` (iOS) that shows the markers while typing, the
WhatsApp and Discord model, with a button strip and shortcuts that write them. A live-formatting editor
was considered and rejected by the owner as the "heavy" experience the feature exists to avoid.

## The grammar

### Inline (within one line; a marker never spans a newline)

| Style | Markers |
|---|---|
| bold | `**text**` |
| italic | `*text*` or `_text_` |
| underline | `__text__` |
| strikethrough | `~~text~~` |
| bold + italic | `***text***` (`___text___` is underline + italic) |

- A marker is a run of one to three identical characters; the run length picks the style. A run of `~`
  means something only when it is exactly two. Longer runs are literal text.
- **Word boundaries.** An opener is preceded by the start of the line or a non-alphanumeric character
  and followed by a non-whitespace character. A closer is preceded by a non-whitespace character and
  followed by the end of the line or a non-alphanumeric character. So `snake_case_name`, `2*3*4`,
  `file_` and `**bold**text` stay literal, as does `** not bold **`.
- An opener with no valid closer on its line is text. A closer must be a run of the same length as its
  opener, so `*a **b** c*` is italic with bold inside.
- Styles nest; the innermost open style closes first. Content between a pair is parsed recursively.
- **Opaque tokens** the scanner steps over without looking for markers: `@{personId}` mention tokens (no
  whitespace or `{` inside, at most 128 characters) and `http://`/`https://` URLs, which run to the next
  whitespace minus a trailing run of sentence punctuation (`.,;:!?'")]}>…`) and marker characters. That is
  what makes `**https://x.org**` bold the whole link and keeps `_` inside a URL from opening italics. A
  URL that genuinely ends in `_`, `*` or `~` loses that character to the surrounding text (accepted).
- No backslash escapes. A literal `**` is only possible where the boundary rules make it literal.

### Blocks (line by line)

| Line | Meaning |
|---|---|
| optional whitespace, then `- `, `* ` or `• `, then content | bullet item |
| optional whitespace, then 1 to 3 digits, `.` or `)`, a space, then content | numbered item |
| anything else | paragraph line |

- Consecutive lines of one list kind form one list; a blank line, a paragraph line or a line of the other
  kind ends it. Lists are flat: indentation is ignored, so a pasted nested list comes out as one list.
- A numbered list starts at its first item's number (`<ol start="3">`); later items count on from there
  whatever they were typed as. The plain projection numbers them the same way.
- Paragraph lines keep their newlines (the website's `white-space: pre-wrap`; `<br />` in mail, which also
  fixed the old collapse of multi-line bodies in mail).

### The plain projection

`plain()` is the body with its markers removed, one output line per source line, bullets as `• ` and
numbered items as `N. `. Push alert bodies (`PushChatNotifier.bodyOf`), reply snippets (`ChatQuote.from`,
before its 160-code-point cut) and the app's copy/accessibility text use it. Mention tokens are kept for
the name resolver.

### Debug notation (the conformance table)

Paragraph lines joined by `\n` inside `<p>...</p>`; styles as `<b> <i> <u> <s>`; lists as
`<ul><li>..</li></ul>` and `<ol start=N><li>..</li></ol>`. Text is NOT escaped in this notation; it is a
parse-tree dump, not HTML.

## Things to know

- Existing messages that happen to contain a valid marker pair (`*please* read`) now render formatted. That
  is the intended reading of such text.
- The 200-code-point notification snippet (`ChatNotifications.snippet`) is cut before rendering, so a pair
  cut in half renders as its literal marker; harmless and rare.
- The website's "Edit message" dialog shows the stored body (tokens and markers) and gets the shortcuts,
  not the strip. iOS edits start from the stored body too (`ChatMessageRow.rawBody`), which also fixed
  mention tokens being flattened on edit.
- Out of scope and unchanged: the support channel page (`SupportChatCommands.renderBody`, its own link
  rule), the audit snapshot of a deleted body (raw content is the point), nested lists, escapes.
- Web composer shortcuts: Ctrl/Cmd+B, I, U; Ctrl/Cmd+Shift+X strikethrough; Ctrl/Cmd+Shift+8 bullets;
  Ctrl/Cmd+Shift+7 numbered; Enter on a list line continues it, Enter on an empty item ends it. The strip
  appears while the box has focus; its buttons cancel `mousedown` so the selection survives the click.
- The strip, the shortcuts and the renderer are wired by DELEGATED document listeners and on-demand
  lookups: PrimeFaces replaces the textarea on every send (`ChatMentionMenuPwIT` explains the trap).
