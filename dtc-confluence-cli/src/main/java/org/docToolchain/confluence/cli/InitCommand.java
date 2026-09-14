package org.docToolchain.confluence.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Writes a configuration to start from.
 *
 * <p>What it writes is the smallest file that publishes something: where Confluence is, which
 * space, and which file to send. Everything else has a default worth keeping, and a configuration
 * that lists its defaults teaches nobody which of them were a decision.</p>
 */
@Command(name = "init", description = "Write a minimal configuration to start from.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class)
public class InitCommand implements Callable<Integer> {

    @Option(names = {"-d", "--doc-dir"}, defaultValue = ".",
            description = "Directory to write the configuration into. Default: ${DEFAULT-VALUE}")
    private String docDir;

    @Option(names = "--api",
            defaultValue = "https://confluence.example.com",
            description = "Confluence API URL to write. Default: ${DEFAULT-VALUE}")
    private String api;

    @Option(names = "--space", defaultValue = "SPACEKEY",
            description = "Space key to write. Default: ${DEFAULT-VALUE}")
    private String spaceKey;

    @Option(names = "--ancestor-id",
            description = "Id of the page to publish under. Left as a comment if not given.")
    private String ancestorId;

    @Option(names = "--file", defaultValue = "build/html5/manual.html",
            description = "HTML file to publish, relative to the document directory. "
                    + "Default: ${DEFAULT-VALUE}")
    private String file;

    @Option(names = "--force", description = "Overwrite an existing configuration.")
    private boolean force;

    @Override
    public Integer call() throws Exception {
        Path target = new File(docDir, ConfigurationFile.DEFAULT_NAME).toPath();
        if (Files.exists(target) && !force) {
            System.err.println(target + " exists already; pass --force to replace it.");
            return 1;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, content(), StandardCharsets.UTF_8);

        System.out.println("Wrote " + target);
        System.out.println();
        System.out.println("Next:");
        System.out.println("  1. put your Confluence URL, space key and ancestor id in it");
        System.out.println("  2. export CONFLUENCE_BEARER_TOKEN=... "
                + "(or CONFLUENCE_CREDENTIALS=user:token)");
        System.out.println("  3. dtc-confluence verify -d " + docDir);
        return 0;
    }

    /**
     * @return the file to write, commented so that each line says what it decides
     */
    private String content() throws IOException {
        String ancestor = ancestorId == null
                ? "      # ancestorId: '123456'   # the page to publish under; "
                        + "take it from a Confluence URL"
                : "      ancestorId: '" + ancestorId + "'";
        return """
                # Configuration for dtc-confluence.
                # Only what has to be said is here; everything else has a default.

                confluence:
                  # Where Confluence is. Data Center and Server end at /confluence or similar;
                  # a Cloud URL ends in .atlassian.net/wiki and switches the API version by itself.
                  api: %s

                  spaceKey: %s

                  # 0 puts the whole document on one page, 1 gives a page per top-level section,
                  # 2 also splits sub-sections.
                  subpagesForSections: 1

                  input:
                    - file: %s
                %s

                # Credentials are read from the environment, never from this file:
                #   CONFLUENCE_BEARER_TOKEN=...        a personal access token
                #   CONFLUENCE_CREDENTIALS=user:token  where the instance wants basic auth
                """.formatted(api, spaceKey, file, ancestor);
    }
}
