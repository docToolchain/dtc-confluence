/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.context;

/** Builds an {@link IContext}, either empty or as a copy of an existing one with fields replaced. */
public class ContextBuilder {

    private final Context context;

    private ContextBuilder() {
        context = new Context();
    }

    /** A builder for a context at the start of a document: no list, no table, no line, no space owed. */
    public static ContextBuilder build() {
        return new ContextBuilder();
    }

    /** A builder pre-filled from {@code context}, for changing one field and keeping the rest. */
    public static ContextBuilder build(IContext context) {
        return build().withContext(context);
    }

    public ContextBuilder withContext(IContext originalContext) {
        context.setListType(originalContext.getListType());
        context.setCellSeparators(originalContext.getCellSeparators());
        context.setLineStarted(originalContext.isLineStarted());
        context.setSpaceNeeded(originalContext.isSpaceNeeded());
        context.setVerbatim(originalContext.isVerbatim());
        return this;
    }

    public ContextBuilder withListType(ListType listType) {
        context.setListType(listType);
        return this;
    }

    public ContextBuilder withCellSeparators(String cellSeparators) {
        context.setCellSeparators(cellSeparators);
        return this;
    }

    public ContextBuilder withLineStarted(boolean lineStarted) {
        context.setLineStarted(lineStarted);
        return this;
    }

    public ContextBuilder withSpaceNeeded(boolean spaceNeeded) {
        context.setSpaceNeeded(spaceNeeded);
        return this;
    }

    public ContextBuilder withVerbatim(boolean verbatim) {
        context.setVerbatim(verbatim);
        return this;
    }

    public IContext create() {
        return context;
    }
}
