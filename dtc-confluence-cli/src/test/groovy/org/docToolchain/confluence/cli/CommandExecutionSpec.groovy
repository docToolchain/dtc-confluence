package org.docToolchain.confluence.cli

import org.docToolchain.tasks.DocToolchainTask
import picocli.CommandLine
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States what a command does with the configuration it loaded, and what it returns.
 *
 * The task is where the network starts, so it is replaced here: everything up to that point is
 * the command's own work and worth pinning.
 */
class CommandExecutionSpec extends Specification {

    @TempDir
    Path docDir

    /** Records what it was handed instead of talking to Confluence. */
    private static class RecordingTask extends DocToolchainTask {
        static ConfigObject seenConfig
        static String seenDocDir
        static boolean executed

        RecordingTask(ConfigObject config) { super(config) }

        @Override
        void execute() { executed = true }
    }

    private TaskFactory recording() {
        return { ConfigObject config, String dir ->
            RecordingTask.seenConfig = config
            RecordingTask.seenDocDir = dir
            return new RecordingTask(config)
        } as TaskFactory
    }

    def setup() {
        RecordingTask.seenConfig = null
        RecordingTask.seenDocDir = null
        RecordingTask.executed = false
        docDir.resolve('config.groovy').toFile().text = """
            confluence = [:]
            confluence.with {
                api = 'https://cwiki.apache.org/confluence'
                spaceKey = 'SPACE'
                input = []
            }
        """
    }

    private int run(Object command, String... extra) {
        command.useTaskFactory(recording())
        return new CommandLine(command)
            .execute(['-d', docDir.toString(), '-c', 'config.groovy', *extra] as String[])
    }

    def 'publish loads the configuration, runs the task and reports success'() {
        when:
            def exit = run(new PublishCommand())

        then:
            exit == 0
            RecordingTask.executed

        and: 'the task gets the configuration that was read and the document root that was named'
            RecordingTask.seenConfig.confluence.spaceKey == 'SPACE'
            RecordingTask.seenDocDir == docDir.toString()
    }

    def 'verify runs its task too'() {
        when:
            def exit = run(new VerifyCommand())

        then:
            exit == 0
            RecordingTask.executed
    }

    def 'wipe runs only when the confirmation is given'() {
        when:
            def exit = run(new WipeCommand(), '--yes-delete-every-page')

        then:
            exit == 0
            RecordingTask.executed
    }

    def 'wipe without the confirmation deletes nothing'() {
        when:
            def exit = run(new WipeCommand())

        then: 'the command line refuses the call before the task is ever built'
            exit != 0
            !RecordingTask.executed
    }

    def 'an override on the command line reaches the task'() {
        when:
            run(new PublishCommand(), '--api', 'https://given.example.org')

        then:
            RecordingTask.seenConfig.confluence.api == 'https://given.example.org'
    }

    def 'a missing configuration is reported rather than run with nothing'() {
        given:
            docDir.resolve('config.groovy').toFile().delete()

        when:
            def exit = run(new PublishCommand())

        then:
            exit != 0
            !RecordingTask.executed
    }
}
