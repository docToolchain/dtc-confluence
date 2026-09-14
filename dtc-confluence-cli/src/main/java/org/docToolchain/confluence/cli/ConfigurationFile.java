package org.docToolchain.confluence.cli;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Finds the configuration file when the command line does not name one.
 *
 * <p>The names are tried in order and the first that exists wins. YAML comes first because it is
 * the format this project is configured in; the docToolchain Groovy file is still recognised, so a
 * project that has one keeps working without being converted.</p>
 *
 * <p>The default name is hidden, because it configures the tool rather than describing the
 * documentation - it belongs with {@code .gitignore} and {@code .editorconfig}, not with the
 * sources.</p>
 */
final class ConfigurationFile {

    static final String DEFAULT_NAME = ".dtc-confluence.yaml";

    private static final List<String> CANDIDATES = List.of(
            DEFAULT_NAME,
            ".dtc-confluence.yml",
            "dtc-confluence.yaml",
            "dtc-confluence.yml",
            "docToolchainConfig.groovy");

    private ConfigurationFile() {
    }

    /**
     * @param docDir the directory to look in
     * @return the name of the first candidate that exists there
     * @throws FileNotFoundException naming every candidate, so the message says what to create
     */
    static String find(String docDir) throws FileNotFoundException {
        for (String candidate : CANDIDATES) {
            if (new File(docDir, candidate).isFile()) {
                return candidate;
            }
        }
        throw new FileNotFoundException("No configuration found in '" + docDir + "'. Looked for "
                + String.join(", ", CANDIDATES)
                + ". Run 'dtc-confluence init' to write one, or name it with --config.");
    }

    /**
     * @return the names that are looked for, so help and messages need not repeat them
     */
    static List<String> candidates() {
        return CANDIDATES;
    }
}
