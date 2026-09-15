package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * States how a page title becomes a file name, and how a page finds the folders it sits in.
 */
class PageNamingSpec extends Specification {

    private static final Map PAGES = [
        '1': [filename: 'Root', adocFilename: 'Root', parentId: '0'],
        '2': [filename: 'PROJ_Child', adocFilename: 'Child', parentId: '1'],
        '3': [filename: 'Grandchild', adocFilename: 'Grandchild', parentId: '2']]

    def 'a title becomes a name a path accepts'() {
        expect:
            PageNaming.sanitizeFilename(title) == expected

        where:
            title               || expected
            'A Page'            || 'A_Page'
            'a/b\\c'            || 'a_b_c'
            'many   spaces'     || 'many_spaces'
            'Straße 1'          || 'Stra_e_1'
    }

    def 'an umlaut is spelt out, and loses its case doing so'() {
        expect: """Measured rather than assumed: the replacement writes the lower-case pair for
                 both cases, so U-umlaut becomes "ue" and not "Ue". Carried over unchanged from
                 docToolchain - changing it would rename the exported file of every page whose
                 title has an umlaut in it."""
            PageNaming.sanitizeFilename('Ärger Öl Über') == 'aerger_oel_ueber'
            PageNaming.sanitizeFilename('ärger öl über') == 'aerger_oel_ueber'
    }

    def 'a sharp s is not spelt out at all'() {
        expect: 'the same carried-over behaviour: it is simply not in the list'
            PageNaming.sanitizeFilename('Größe') == 'Groe_e'
    }

    def 'a root page sits in no folder'() {
        expect:
            PageNaming.folderStructure(PAGES, '1') == []
    }

    def 'a page sits in the folders of its ancestors, outermost first'() {
        expect:
            PageNaming.folderStructure(PAGES, '3') == ['Root', 'PROJ_Child']
    }

    def 'the adoc structure uses the prefix-stripped names'() {
        expect: 'which is what the .adoc files are written under'
            PageNaming.adocFolderStructure(PAGES, '3') == ['Root', 'Child']
    }

    def 'a page with no adoc name falls back to its file name'() {
        given:
            def pages = ['1': [filename: 'Root', parentId: '0'],
                         '2': [filename: 'Child', parentId: '1']]

        expect:
            PageNaming.adocFolderStructure(pages, '2') == ['Root']
    }

    def 'a root recognised however its missing parent is spelled'() {
        expect: 'the walk writes a string, docToolchain wrote a number, and either means no parent'
            PageNaming.folderStructure(['1': [filename: 'Root', parentId: parentId]], '1') == []

        where:
            parentId << ['0', 0, 'null', null, '']
    }

    def 'a parent that is not in the export yields no folders'() {
        given: 'a subtree export names an ancestor it does not contain'
            def pages = ['2': [filename: 'Child', parentId: '99']]

        expect:
            PageNaming.folderStructure(pages, '2') == []
    }

    def 'a page that is its own ancestor does not recurse forever'() {
        given:
            def pages = ['1': [filename: 'A', parentId: '2'],
                         '2': [filename: 'B', parentId: '1']]

        when:
            def folders = PageNaming.folderStructure(pages, '1')

        then:
            noExceptionThrown()
            folders.size() <= 2
    }

    def 'a page nobody knows has no folders'() {
        expect:
            PageNaming.folderStructure([:], 'nope') == []
    }
}
