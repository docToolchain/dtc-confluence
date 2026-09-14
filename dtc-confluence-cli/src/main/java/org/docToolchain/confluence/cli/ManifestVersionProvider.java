package org.docToolchain.confluence.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Reads the version off the package, so that {@code --version} cannot drift from what was built.
 *
 * <p>The assembled jar carries {@code Implementation-Version} in its manifest. Running from a
 * classes directory there is no manifest, and then the version is simply unknown rather than a
 * number that might be wrong.</p>
 */
public class ManifestVersionProvider implements IVersionProvider {

    private static final String UNKNOWN = "unknown";

    // picocli instantiates this from its own package, so both the class and its constructor have
    // to be reachable from outside this one.
    public ManifestVersionProvider() {
    }

    @Override
    public String[] getVersion() {
        String version = ManifestVersionProvider.class.getPackage().getImplementationVersion();
        return new String[] {"dtc-confluence " + (version == null ? UNKNOWN : version)};
    }
}
