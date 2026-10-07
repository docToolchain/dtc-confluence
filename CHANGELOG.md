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
- `confluence.export.converter` chooses what turns the HTML into AsciiDoc: `pandoc` (the default)
  or `native`, which converts in process and needs nothing installed. `native` is a proof of
  concept — it is the only one whose footnote links work, and it still gets nested lists and block
  images wrong. `docs/html2adoc-poc.adoc` compares the two construct by construct.

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
- Exporting needs pandoc unless `confluence.export.converter` is set to `native`, which is not
  yet good enough to be the default; publishing needs neither.
