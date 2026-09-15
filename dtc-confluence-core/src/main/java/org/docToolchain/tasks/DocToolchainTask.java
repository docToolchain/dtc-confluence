package org.docToolchain.tasks;

import groovy.util.ConfigObject;
import org.docToolchain.configuration.ConfigService;

/**
 * A unit of work driven by a configuration.
 *
 * <p>The configuration arrives as the {@link ConfigObject} both supported formats parse into, and
 * is read through {@link ConfigService} rather than directly, so that a missing entry means the
 * same thing everywhere.</p>
 */
public abstract class DocToolchainTask {

    protected final ConfigService configService;

    protected DocToolchainTask(ConfigObject config) {
        this.configService = new ConfigService(config);
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public abstract void execute();
}
