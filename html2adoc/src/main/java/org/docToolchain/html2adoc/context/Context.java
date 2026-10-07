/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.context;

/** The one implementation of {@link IContext}; only {@link ContextBuilder} may populate it. */
public class Context implements IContext {

    private ListType listType;
    private String cellSeparators = "";
    private boolean lineStarted;
    private boolean spaceNeeded;
    private boolean verbatim;

    Context() {
    }

    @Override
    public ListType getListType() {
        return listType;
    }

    void setListType(ListType listType) {
        this.listType = listType;
    }

    @Override
    public String getCellSeparators() {
        return cellSeparators;
    }

    void setCellSeparators(String cellSeparators) {
        this.cellSeparators = cellSeparators;
    }

    @Override
    public boolean isLineStarted() {
        return lineStarted;
    }

    void setLineStarted(boolean lineStarted) {
        this.lineStarted = lineStarted;
    }

    @Override
    public boolean isVerbatim() {
        return verbatim;
    }

    void setVerbatim(boolean verbatim) {
        this.verbatim = verbatim;
    }

    @Override
    public boolean isSpaceNeeded() {
        return spaceNeeded;
    }

    void setSpaceNeeded(boolean spaceNeeded) {
        this.spaceNeeded = spaceNeeded;
    }
}
