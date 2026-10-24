# Changelog

This file is the release notes: JReleaser reads it when a release is cut.

## 0.1.0 (unreleased)

First release. Confluence support extracted from docToolchain 3.4.2, ported to Java, with the
publisher verified against a real instance (ASF cwiki, Confluence Data Center 9.2.21).

### Publishing

- `publish` writes AsciiDoctor HTML to Confluence: page trees from `subpagesForSections`, footnotes
  with working anchors, callouts in code blocks (three styles, chosen by configuration),
  admonitions, collapsible blocks, description lists, images and non-image attachments.
- `--dry-run` says what a run would change — create, update, unchanged, or fail on a title that is
  taken — and writes nothing.
- `--move` re-parents a page this publisher wrote when the document asks for it elsewhere, instead
  of failing on the unique-title rule. A page written by somebody else is never moved.
- Every run names the pages below the document that an earlier run wrote and this one did not
  touch, which is what a renamed heading leaves behind.
- An attachment is published under the name Confluence knows it by: the `<version>_` prefix an
  export writes into a file name is provenance, not a name, and a renamed attachment leaves a
  draw.io diagram pointing at nothing. The prefix is dropped only where the shorter name stays
  unique on the page: `1_diagram.png` beside `2_diagram.png`, or screenshots numbered
  `01_step.png` and `02_step.png`, keep their names, and the run says which files and why.
- An attachment is uploaded under the name it has. The upload used to percent-encode it, so a
  picture named `Übersicht.png` became an attachment called `%C3%9Cbersicht.png` while the page
  body still referred to the name with the umlaut - the page pointed at an attachment that does
  not exist and the picture did not render. Measured on Data Center 9.2.21.
- An attachment somebody else wrote since the export this document came from is not overwritten.
  The signal is the revision, not the hash in the version comment: the draw.io app writes its own
  comment, which no hash comparison can recognise, so every publish used to discard its edit.
  `confluence.foreignAttachments` (`--foreign-attachments`) takes `fail` (the default), `skip` or
  `overwrite`. An attachment no export ever saw and nothing here wrote is left alone.
- A picture that carries a draw.io diagram is published as that diagram: the macro, the `mxfile`
  attached under the picture's base name with no extension, and the picture as `<name>.png` for the
  preview. draw.io keeps its own source inside what it exports, so one `image::x.png[…]` means a
  picture or an editable diagram and the file decides which. A picture that carries no diagram is
  left exactly as it was, and there is nothing to configure. An SVG needs the PNG of the same name
  beside it to serve as the preview; without one it stays a picture and the run says so.
- The API version is derived from the API URL, so a Data Center URL no longer reaches a v2
  endpoint and a 404.

### Exporting

- `export` fetches a page and everything below it and writes AsciiDoc, with attachments, a menu
  and a report of the macros it could not translate. Needs pandoc on the PATH.
- An inline comment keeps its anchor across a round trip. The export carries the marker's
  `ac:ref` out as a role on the marked span, and a publish writes it back as
  `ac:inline-comment-marker`, so a comment made in Confluence stays on the words it was made on
  instead of being left dangling. The comment itself is never touched, so author, date and thread
  stay as they are.
- The draft the draw.io app keeps beside every diagram (`~<diagram>.tmp`) is not exported, so a
  round trip no longer carries a file nobody wrote in both directions.
- A draw.io diagram is exported as one file: the `mxfile` is put inside the preview, the bare one is
  dropped, and the document refers to a picture that is also the diagram - so publishing that
  document again writes the macro back instead of flattening it. The source is read from the
  `mxfile` and never from the preview, which is derived and may be older. A preview older than its
  diagram is reported, naming the view, and drawn again only where draw.io is on the `PATH` and says
  it numbers pages from one, which it has done since 27.0.2; the exported file carries the current
  diagram either way. The file is named `<picture revision>-<diagram revision>_<name>.png`, because
  it goes back as two attachments and each is guarded by the revision it came from. Measured: export,
  publish, export again returns a byte-identical file and an identical `mxfile`.
- A macro pinned to one view of a multi-view diagram exports as a picture of *that* view, where
  before all the macros on one file exported as the file's first page.
- A `jira` macro becomes a link rather than its own parameters: one issue becomes a link to it, a
  counted query an inline link to the issues it counted, a listing the query plus a link to them.
  The Jira's address is read off the page Confluence rendered, so nothing has to be configured.
- An exported page carries its title as the document title (`= Title`, with `:showtitle:`) rather
  than as a section of its own. The page reads on its own, its own headings sit inside its title
  instead of beside it, and the tree reaches one page deeper before AsciiDoc's six heading levels
  run out — six pages, where it was five.
- A link to a page the export does not hold keeps its destination: it becomes a URL into the
  Confluence it was read from, where it used to become the bare words of its link text. A link to
  an anchor the page sets becomes a reference into the exported document. What no URL can be built
  for - a mention of a user, which the storage format identifies by a key and not by the name a
  profile URL is built from - is reported among the constructs that did not translate, so a lost
  link is never silent.

### Configuration and tooling

- YAML configuration (`.dtc-confluence.yaml`), with docToolchain's `docToolchainConfig.groovy`
  still read, so an existing project keeps working unconverted.
- `init` writes a configuration to start from; `verify` checks URL and credentials; `wipe` deletes
  every page in a space and refuses to run without `--yes-delete-every-page`.
- Credentials come from the environment (`CONFLUENCE_BEARER_TOKEN` or `CONFLUENCE_CREDENTIALS`),
  never from the configuration file.

### Known limits

- Confluence Cloud is implemented but has never been verified against a real instance.
- A file an author named `2024_report.png` is published as `report.png`, because that is the shape
  an export's version prefix has. The page refers to the same name, so it still shows, and the
  name is kept as it is where another file on the page would collide with the shorter form.
- An attachment name containing whitespace, a colon or a path separator does not survive a round
  trip: the export sanitises it, so `my diagram.png` is published as `my_diagram.png` - a second
  attachment beside the original rather than a new revision of it. Names without those characters
  survive exactly. `docs/decisions.adoc` says what it would take to fix.
- A view pinned with an `aspect` does not come back pinned: it exports as a picture of that view,
  and republished it is a picture, because the aspect is nowhere in the document to put back. One
  file per view has no pinned views and does not meet this.
- On a Confluence without the draw.io app, a picture that carries a diagram becomes a macro nothing
  renders. The run says what it did, per picture; there is no setting to turn it off.
- A page is identified by its title, so renaming a heading creates a new page and leaves the old
  one behind. The run reports it; it does not fix it.
- The public API still exposes Groovy's `ConfigObject`.
- Exporting needs pandoc; publishing does not.
- An inline comment's anchor survives only where the AsciiDoc came out of an export and still
  carries the ref. A page written as AsciiDoc from the start and commented on in Confluence loses
  its anchors on the next publish.
