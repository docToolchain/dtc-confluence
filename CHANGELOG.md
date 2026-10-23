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
- The API version is derived from the API URL, so a Data Center URL no longer reaches a v2
  endpoint and a 404.

### Exporting

- `export` fetches a page and everything below it and writes AsciiDoc, with attachments, a menu
  and a report of the macros it could not translate. Needs pandoc on the PATH.
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
- A page is identified by its title, so renaming a heading creates a new page and leaves the old
  one behind. The run reports it; it does not fix it.
- The public API still exposes Groovy's `ConfigObject`.
- Exporting needs pandoc; publishing does not.
