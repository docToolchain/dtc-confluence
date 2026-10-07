/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.context;

/**
 * What a converter needs to know about the position it writes at, because HTML carries it in the
 * tree and AsciiDoc carries it in the characters already written.
 *
 * <p>A context is immutable: a converter that changes the position returns a new one, built with
 * {@link ContextBuilder}.</p>
 */
public interface IContext {

    /** The list currently being converted, or {@code null} outside any list. */
    ListType getListType();

    /** Whether anything has been written on the current output line. */
    boolean isLineStarted();

    /**
     * The table cell separators in force where the text is written, empty outside any table.
     *
     * <p>Every one of them has to be escaped in text, because each ends a cell of one of the tables
     * this text sits in. Markup the converters write themselves is not escaped, which is the whole
     * reason this travels with the position rather than being applied to finished output.</p>
     */
    String getCellSeparators();

    /**
     * Whether the text written here is verbatim, which is what {@code <pre>} says about its
     * content.
     *
     * <p>Verbatim text is written through unescaped. AsciiDoc substitutes nothing inside a literal
     * or listing block and strips no backslash there, so an escape put in would be printed: a
     * {@code ${HOME}} escaped against attribute substitution reaches the page as
     * {@code $\{HOME}}.</p>
     */
    boolean isVerbatim();

    /**
     * Whether a separating space is owed to the next text written.
     *
     * <p>HTML collapses whitespace between elements; AsciiDoc does not, so the space that
     * {@code <p>a <strong>b</strong></p>} implies has to be put back deliberately.</p>
     */
    boolean isSpaceNeeded();
}
