# Corpus inventory

What the converter has to convert, counted. This is the list of constructs the emitter must
support, and the evidence for what it may ignore.

## Where the corpus comes from

The `.html` files beside this one are not written by hand. They are what
`ConfluenceConverter.fixBody` produces for the storage format this project itself publishes:

1. `dtc-confluence-core/src/test/resources/publisher/smoke-subpages-{0,1,2}.txt` are golden
   transcripts of a real publish of `smoke-input.html` — the same document cut into one, three and
   four Confluence pages.
2. `Html2AdocCorpusSpec` (in `dtc-confluence-core`) replays each transcript through `fixBody`,
   which is the step an export performs before anything converts HTML: every `ac:`/`ri:` macro is
   already translated to HTML or to a `%%TOKEN%%`. **That** is what this module is fed at runtime —
   never raw storage format.
3. The spec compares its result against the checked-in files, so a change in `MacroTranslator` or
   `fixBody` turns red here. Regenerate with
   `./mvnw -pl dtc-confluence-core test -Dtest=Html2AdocCorpusSpec -Dcorpus.update=true`, and
   update this inventory with it.

The eight pages of the three transcripts contain six distinct bodies — a subpage split leaves some
pages untouched — and the duplicates are dropped, so that the counts below are not weighted
towards whatever happens to survive a split.

| Corpus file | Elements |
| --- | --- |
| `smoke-subpages-0-docToolchain_Confluence_Smoke_Test.html` (whole document on one page) | 66 |
| `smoke-subpages-1-First_Page.html` | 29 |
| `smoke-subpages-1-Second_Page.html` | 26 |
| `smoke-subpages-2-First_Page.html` | 20 |
| `smoke-subpages-2-Subsection.html` | 7 |
| `smoke-subpages-1-docToolchain_Confluence_Smoke_Test.html` (root page of a split) | 2 |

`publisher/callout-input.html` is not in the corpus: published, it yields one more `code` macro and
no construct that is not already here.

## Elements

150 elements, counted with jsoup's HTML parser — the one `Html2Adoc` uses. "Files" is how many of
the six pages the element appears on. The verdict names a converter in
`org.docToolchain.html2adoc.convert` or says why none is needed; each was read, and each was
checked against a run of the seeded converter over this corpus.

| Element | Count | Files | Verdict | Note |
| --- | --- | --- | --- | --- |
| `p` | 56 | 6 | handled | `ParagraphConverter`; a whitespace-only one is dropped |
| `div` | 25 | 5 | handled | `TransparentConverter` drops the wrapper and keeps the content, and unlike the fallback it owes the next text no space; `div.imageblock` and `div.footnote` still want converters of their own, see *Gaps* |
| `li` | 9 | 3 | handled | `ListItemConverter` |
| `sup` | 7 | 4 | handled | `SuperscriptConverter` writes `^…^`; every `sup` here is a footnote marker, see *Gaps* |
| `ul` | 6 | 3 | handled | `ListConverter` |
| `br` | 4 | 2 | handled | `BrConverter` writes a space, not an AsciiDoc hard break |
| `col` | 4 | 2 | not needed | carries only `style="width: 50%;"`; the column count is measured from the cells instead |
| `hr` | 4 | 4 | handled | `HorizontalRuleConverter` writes `'''` on a line of its own |
| `td` | 4 | 2 | handled | `TableTdConverter`; every cell here is the `<td><p class="tableblock">…</p></td>` that Confluence writes, which the converter reads as one line of text and not as a block |
| `th` | 4 | 2 | handled | `TableTdConverter` |
| `tr` | 4 | 2 | handled | `TableTrConverter` |
| `code` | 3 | 3 | handled | `MonospaceConverter` |
| `em` | 3 | 3 | handled | `EmphasisConverter` |
| `h1` | 3 | 2 | handled | `HeaderConverter` |
| `strong` | 3 | 3 | handled | `StrongConverter` |
| `colgroup` | 2 | 2 | not needed | wrapper of `col` |
| `img` | 2 | 2 | handled | `ImageConverter`, `src` only |
| `table` | 2 | 2 | handled | `TableConverter` writes `[cols="n*"]` and the delimiters, so every row lands inside the block — including a `thead` row, which the block opened by `tbody` used to escape |
| `tbody` | 2 | 2 | handled | `TransparentConverter`; the delimiters it used to write moved to `TableConverter` |
| `thead` | 2 | 2 | handled | `TransparentConverter` for its rows, and `TableConverter` reads it as the `options="header"` of the table |
| `h2` | 1 | 1 | handled | `HeaderConverter` |

## Attributes

| Attribute | Count | On | Verdict | Note |
| --- | --- | --- | --- | --- |
| `class` | 45 | `div`, `p`, `sup`, `table`, `td`, `th` | not needed | no converter reads it; the block semantics it carries are the evidence under *Gaps* |
| `id` | 10 | `div`, `h1`, `h2` | not needed | every one of them is also present as a `%%ANCHOR%%` token, which is what the export turns into an AsciiDoc anchor |
| `style` | 4 | `col` | not needed | `width: 50%;` only |
| `src` | 2 | `img` | handled | `ImageConverter` |
| `align` | 2 | `img` | **missing** | `center`, dropped |
| `width` | 2 | `img` | **missing** | `64`, dropped |

## Placeholder tokens

Not HTML, but part of the input: `MacroTranslator` leaves these for `AdocOutput` to spell out after
the HTML has been converted. They must survive verbatim. They do — `TextNodeConverter` carries them
as text — and `AdocOutput` tolerates the whitespace the converters put around them.

| Token | Count | Becomes |
| --- | --- | --- |
| `%%ANCHOR%%` / `%%ANCHOR-END%%` | 15 / 15 | `[[_name]]` |
| `%%CRLF%%` | 15 | a line break |
| `%%SOURCE-BEGIN%%` / `%%SOURCE-END%%` | 3 / 3 | `[source, language]` |
| `%%ADMON-BEGIN-NOTE%%` / `%%ADMON-END%%` | 3 / 3 | `[NOTE]` and `====` |
| `%%BLOCK-TITLE%%` / `%%BLOCK-TITLE-END%%` | 3 / 3 | `.Title`, on the line above its block |

## Gaps

Shapes the corpus holds that no single element names, found by converting it:

* **Block images come out inline.** `ImageConverter` writes `image::` only for an `img` whose
  parent is a `p` or the `body`. In the corpus an image block is `div.imageblock > div.content >
  img`, so it is written as `image:…[]` mid-line. `AdocOutput` repairs a line that starts with one;
  this one does not start a line.
* **Footnotes are not recognised.** A footnote reference arrives as
  `sup.footnote > text + %%ANCHOR%% + xref:…`, and the definitions as `div.footnotes > div.footnote`.
  Converted element by element this becomes `^[^ … ]`, not a footnote.
* **A nested list comes out flat.** `ListConverter` records which kind of list its items are in and
  not how deep they stand, so the `ul` inside a `li` writes `*` like its parent. `nested` becomes a
  third sibling of `Second item` rather than its child.

## What the corpus cannot say anything about

Absent from all six pages, so nothing here is evidence for or against them: `a`, `ol`, `pre`, `dl`,
`blockquote`, `span`, `sub`, `h3`–`h6`, nested tables, `colspan`/`rowspan`, and the
`%%STATUS%%`, `%%EXPAND%%`, `%%ADMON-TITLE%%`, `%%DISCRETE%%` and `%%TABLE-ROWHEADER%%` tokens. A
converter for any of them is written blind until a page that has one is exported into the corpus.

Two of those are reached by the export on every page of the right shape, which makes their absence
from the corpus a hole in the measurement rather than a construct nobody meets:

* **`a`.** `ConfluenceConverter.rewriteLinksIntoThisExport` replaces the target of every in-export
  cross-page link with a fragment-less `X/Y.html`, and the `view-file` macro writes
  `<a href="…/1_spec.docx">` for every attachment that is not an image or a PDF. The corpus has no
  `a` at all because its cross-page links arrive as bare `xref:` *text* from `MacroTranslator.link`.
  `LinkConverter` converts both shapes and `LinkSpec` is the only evidence for it.
* **`pre`.** The code macro's listing block is marked as a `<pre>` by
  `NativeHtmlToAsciidoc.markVerbatimBlocks`, which is what stops `AdocText` escaping the sample. The
  corpus does contain a code macro, but its body is `def hello = "world"` — no character that
  escaping would change — so it cannot tell a converter that escapes the sample from one that does
  not. `PreformattedSpec` and `ExportConverterChoiceSpec` do.

`span` is a third, and needs no evidence: it is dropped by `TransparentConverter`, and dropping a
wrapper says nothing about what it wraps. The table cell is the fourth: `colspan`, `rowspan`, a cell
holding blocks and a table inside a cell are all written by `TableTdConverter` on no corpus evidence
at all, and the stand-in for it is `asciidoctor` — every expectation in `TableSpanSpec` and
`TableCellSpec` was rendered, and the `colspan`, `rowspan` and nesting of the HTML that came out
compared against the HTML that went in. That says the output is AsciiDoc the renderer agrees with;
it does not say Confluence ever exports such a cell.
