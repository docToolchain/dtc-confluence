package org.docToolchain.confluence.cli;

import groovy.util.ConfigObject;
import org.docToolchain.tasks.DocToolchainTask;

/**
 * Builds the task a command runs.
 *
 * <p>The task is where the network starts, so it is the seam: a test can put something else here
 * and state what the command does with the configuration it loaded, and what it returns.</p>
 */
@FunctionalInterface
interface TaskFactory {

    DocToolchainTask create(ConfigObject config, String docDir);
}
