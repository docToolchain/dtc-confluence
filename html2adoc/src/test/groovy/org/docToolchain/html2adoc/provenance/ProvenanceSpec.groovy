/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.provenance

import spock.lang.Specification

/**
 * Keeps the licence header of every file in agreement with where the file came from.
 *
 * This module is a derivative work, and the headers are how a reader of one file learns whose
 * copyright it carries. A header that claims derivation on a file written here credits the upstream
 * author for work that is not his and overstates what the Apache-2.0 seed covers; a file with no
 * header at all states nothing. Which files have an upstream counterpart is recorded in
 * `provenance/upstream-counterparts.txt`, read from the upstream repository at the commit the
 * NOTICE names, and that record is what this spec measures the headers against.
 *
 * A new file therefore has to be entered in the record before this is green, which is the point:
 * the provenance of a file is decided when it is written, by whoever knows the answer.
 */
class ProvenanceSpec extends Specification {

    private static final String RECORD = 'provenance/upstream-counterparts.txt'

    /** The two headers a file of this module may carry, keyed by whether it is derived. */
    private static final Map<Boolean, String> HEADER = [
            (true) : 'Derived from the html2adoc module of',
            (false): 'Written for dtc-confluence; it has no counterpart',
    ]

    private static final List<File> ROOTS = [
            new File('src/main/java/org/docToolchain/html2adoc'),
            new File('src/test/groovy/org/docToolchain/html2adoc'),
    ]

    /** The record, as a map from this module's path to the upstream files it derives from. */
    private static Map<String, List<String>> record() {
        Map<String, List<String>> record = [:]
        ProvenanceSpec.classLoader.getResourceAsStream(RECORD).eachLine('UTF-8') { String line ->
            String text = line.trim()
            if (text.isEmpty() || text.startsWith('#')) {
                return
            }
            def (String path, String upstream) = text.split('=', 2).collect { it.trim() }
            record[path] = upstream == '-' ? [] : upstream.split(',').collect { it.trim() }
        }
        return record
    }

    /** Every source file of the module, by the path the record names it under. */
    private static Map<String, File> sources() {
        Map<String, File> sources = [:]
        ROOTS.each { File root ->
            root.eachFileRecurse { File file ->
                if (file.isFile()) {
                    sources[root.toPath().relativize(file.toPath()).toString()] = file
                }
            }
        }
        return sources
    }

    def 'the record and the module name the same files'() {
        expect:
            sources().keySet().toSorted() == record().keySet().toSorted()
    }

    def 'every file says in its header whether it is derived, and says the same as the record'() {
        given:
            Map<String, List<String>> record = record()

        expect:
            sources().every { String path, File file ->
                String header = file.getText('UTF-8').take(400)
                boolean derived = !record[path].isEmpty()
                assert header.contains(HEADER[derived]), path
                assert !header.contains(HEADER[!derived]), path
                return true
            }
    }

    def 'a derived file names an upstream file of the module that was seeded from'() {
        expect: 'under src/main/java or src/test/java of the upstream html2adoc module'
            record().values().flatten().every { String upstream ->
                assert upstream ==~ /[\w\/]+\.(java|groovy)/, upstream
                return true
            }
    }
}
