/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

/** Bold text: {@code <strong>} becomes {@code *...*}. */
public class StrongConverter extends AbstractFormatConverter {

    public StrongConverter() {
        super("strong", "*");
    }
}
