/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * An inline element that AsciiDoc writes by wrapping its content in one repeated character.
 *
 * <p>How often the character is repeated is not a matter of taste. AsciiDoc has two forms, and
 * {@code *bold*} is only recognised where neither of its characters runs into a word, so a span
 * that touches one needs {@code **bold**} instead; {@link AdocText#constrainedFormWorksAfter} and
 * {@link AdocText#constrainedFormWorksBefore} hold the rule and the reason. Both neighbours are
 * known before anything is written: the one in front is the last character of the output, and the
 * one behind can only block in the single case {@link #followingCharacter} describes.</p>
 *
 * <p>Subscript and superscript pass through this class as well, and never double their character:
 * their form is unconstrained to begin with, which is what lets {@code H~2~O} work mid-word.</p>
 */
abstract class AbstractFormatConverter extends AbstractConverter {

    private final String tagName;
    private final String adocChar;

    AbstractFormatConverter(String tagName, String adocChar) {
        this.tagName = tagName;
        this.adocChar = adocChar;
    }

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return tagName.equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        addSpaceIfNeeded(context, sb);
        String marker = marker(AdocText.lastCharacterOf(sb), followingCharacter(node));
        sb.append(marker);
        int contentStart = sb.length();
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).create();
        Html2Adoc.convert(newContext, sb, node);
        if (sb.length() == contentStart) {
            // An element with nothing in it. Two markers with nothing between them are not an empty
            // span, they are the opening of an unconstrained one that never closes.
            sb.setLength(contentStart - marker.length());
            return context;
        }
        sb.append(marker);
        // The closing character ends the formatted run, so whatever follows is a separate word.
        return ContextBuilder.build(context).withSpaceNeeded(true).create();
    }

    private String marker(char previous, char following) {
        char character = adocChar.charAt(0);
        if (!AdocText.hasConstrainedForm(character)
                || (AdocText.constrainedFormWorksAfter(character, previous)
                        && AdocText.constrainedFormWorksBefore(character, following))) {
            return adocChar;
        }
        return adocChar + adocChar;
    }

    /**
     * The character that will follow this span's closing marker where it could keep the constrained
     * form from being recognised, and {@code '\n'} where it cannot.
     *
     * <p>Only one case has to be looked up. A span that ends where an enclosing {@code <em>} ends is
     * followed by that element's {@code _}, and an underscore is a word character, so
     * {@code <em>a<strong>b</strong></em>} written as {@code _a *b*_} would lose its bold. Anywhere
     * else what comes after is a separating space, written by whichever converter takes the next
     * node, or punctuation, or the end of the line.</p>
     */
    private static char followingCharacter(Node node) {
        for (Node current = node; current.nextSibling() == null; current = current.parentNode()) {
            Node parent = current.parentNode();
            if (!(parent instanceof Element)) {
                return '\n';
            }
            if ("em".equals(((Element) parent).tagName())) {
                return '_';
            }
        }
        return '\n';
    }
}
