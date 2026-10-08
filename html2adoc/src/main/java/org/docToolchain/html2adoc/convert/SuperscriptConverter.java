/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

/** Superscript text: {@code <sup>} becomes {@code ^...^}, which AsciiDoc reads anywhere. */
public class SuperscriptConverter extends AbstractFormatConverter {

    public SuperscriptConverter() {
        super("sup", "^");
    }
}
