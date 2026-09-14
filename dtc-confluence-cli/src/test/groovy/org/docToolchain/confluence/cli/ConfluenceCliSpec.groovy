package org.docToolchain.confluence.cli

import picocli.CommandLine
import spock.lang.Specification

/**
 * States what the command line answers before it ever talks to Confluence.
 */
class ConfluenceCliSpec extends Specification {

    private static String run(String... args) {
        def out = new StringWriter()
        def cmd = new CommandLine(new ConfluenceCli())
        cmd.setOut(new PrintWriter(out))
        cmd.setErr(new PrintWriter(out))
        cmd.execute(args)
        return out.toString()
    }

    def 'the version comes from the manifest rather than from a literal'() {
        expect: 'picocli builds the provider reflectively, from its own package'
            run('--version').startsWith('dtc-confluence ')
    }

    def 'every subcommand answers --help'() {
        expect: 'the root mixin does not reach them, so each one carries its own'
            run(command, '--help').contains("Usage: dtc-confluence ${command}")

        where:
            command << ['publish', 'verify', 'wipe']
    }

    def 'the help of a subcommand names the options it takes'() {
        when:
            def help = run('publish', '--help')

        then:
            help.contains('--doc-dir')
            help.contains('--config')
            help.contains('--api')
    }

    def 'wipe names the confirmation it insists on'() {
        expect:
            run('wipe', '--help').contains('--yes-delete-every-page')
    }

    def 'without a subcommand the usage is shown rather than something being done'() {
        expect:
            run().contains('Usage: dtc-confluence')
    }

    def 'every subcommand reports the version too'() {
        expect: 'picocli does not hand the root provider down, so each one names it'
            run(command, '--version').startsWith('dtc-confluence ')

        where:
            command << ['publish', 'verify', 'wipe']
    }
}
