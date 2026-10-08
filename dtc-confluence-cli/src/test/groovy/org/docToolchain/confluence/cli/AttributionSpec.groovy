package org.docToolchain.confluence.cli

import spock.lang.Specification

/**
 * The attribution the Apache-2.0 module asks for, as the CLI distributes it.
 *
 * The CLI ships as a fat jar built by {@code jar-with-dependencies}, which unpacks every dependency
 * into one tree. Same-path files overwrite each other there, so {@code META-INF/NOTICE} ends up
 * being whichever dependency was unpacked last - for a while an Apache HttpComponents notice of
 * 190 bytes, with the notice naming the upstream author of html2adoc gone from the
 * only artifact anyone installs. The notice therefore also travels under the name of its own
 * module, where nothing else can land on it, and this is the assertion that it does.
 *
 * Read off the classpath rather than out of the jar: the jar is assembled in {@code package},
 * after the tests have run. What a test can check is that the files are where the packaging picks
 * them up from, and that they say what they have to say.
 */
class AttributionSpec extends Specification {

    private static final String NOTICES = 'META-INF/notices/html2adoc'

    def 'the notice of the derived module travels under a path no dependency shares'() {
        when:
            String notice = resource("${NOTICES}/NOTICE")

        then: 'the upstream, its author and its licence, which is what a notice is for'
            notice != null
            notice.contains('asciidoctorj-experiments')
            notice.contains('Jeremie Bresson')
            notice.contains('Apache License, Version 2.0')
    }

    def 'and so does the licence text the notice refers to'() {
        when:
            String licence = resource("${NOTICES}/LICENSE-APACHE-2.0.txt")

        then:
            licence != null
            licence.contains('Apache License')
            licence.contains('Version 2.0, January 2004')
    }

    /** @return the resource as text, or null where nothing is published at that path */
    private static String resource(String path) {
        return AttributionSpec.classLoader.getResourceAsStream(path)?.getText('UTF-8')
    }
}
