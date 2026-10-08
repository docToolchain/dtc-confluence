/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.escape

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * Text whose first characters AsciiDoc would read as the start of a block rather than as text.
 *
 * <p>A backslash is no help here: AsciiDoc decides what a line is before it substitutes anything,
 * and prints the backslash. What does work is an attribute that resolves to nothing in front of the
 * line, so that the line no longer begins with the character and the text is still all that is
 * left of it.</p>
 *
 * <p>The four cases that take the text off the page entirely are a block title, a block attribute
 * line, an attribute entry and a comment. A heading, a list item and a block delimiter keep the
 * text and change what it is.</p>
 */
class LineStartSpec extends Specification {

    def 'block markup at the start of a line is held back: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                   | html                                 | expected
            'block title'          | '<p>.NET is a platform</p>'          | '{empty}.NET is a platform\n\n'
            'ordered list item'    | '<p>. and then some</p>'             | '{empty}. and then some\n\n'
            'heading'              | '<p>= not a heading</p>'             | '{empty}= not a heading\n\n'
            'unordered list item'  | '<p>- none -</p>'                    | '{empty}- none -\n\n'
            'starred list item'    | '<p>* and another</p>'               | '{empty}* and another\n\n'
            'list continuation'    | '<p>+</p>'                           | '{empty}+\n\n'
            'block delimiter'      | '<p>----</p>'                        | '{empty}----\n\n'
            'block attribute line' | '<p>[source, groovy]</p>'            | '{empty}[source, groovy]\n\n'
            'attribute entry'      | '<p>:name: not an entry</p>'         | '{empty}:name: not an entry\n\n'
            'comment'              | '<p>// not a comment</p>'            | '{empty}// not a comment\n\n'
    }

    def 'a line that has already started needs no holding back: #name'() {
        expect:
            // The marker is only markup as the first thing on a line. After a heading marker, a
            // list bullet or a cell separator the line is under way and a dot is a dot.
            Html2Adoc.convert(html) == expected

        where:
            name            | html                             | expected
            'heading text'  | '<h2>.NET is a platform</h2>'    | '== .NET is a platform\n\n'
            'list item'     | '<ul><li>.NET</li></ul>'         | '* .NET\n\n'
            'second word'   | '<p>the .NET platform</p>'       | 'the .NET platform\n\n'
    }

    def 'a plain table cell needs no holding back, a block cell does'() {
        given: 'two cells of the same text, one of them made a block cell by its second paragraph'
            def plain = '<table><tbody><tr><td>.NET</td><td>x</td></tr></tbody></table>'
            def block = '<table><tbody><tr><td><p>.NET</p><p>and more</p></td><td>x</td></tr></tbody></table>'

        expect: 'the plain cell writes its text behind the separator, where a dot is a dot'
            Html2Adoc.convert(plain) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|.NET\n' +
                    '|x\n' +
                    '|===\n'

        and: 'the block cell writes its text on a line of its own, where a dot is a block title'
            Html2Adoc.convert(block) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    'a|\n' +
                    '{empty}.NET\n' +
                    '\n' +
                    'and more\n' +
                    '|x\n' +
                    '|===\n'
    }
}
