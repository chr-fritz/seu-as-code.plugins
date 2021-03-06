/*
 *    Copyright (C) 2015 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.seu.as.code.plugins.homebrew

import de.qaware.seu.as.code.plugins.base.DatastoreProvider
import de.qaware.seu.as.code.plugins.base.DatastoreProviderFactory
import de.qaware.seu.as.code.plugins.base.SeuacDatastore
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.internal.ExecAction
import org.gradle.process.internal.ExecActionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.inject.Inject
import java.nio.file.Paths

/**
 * Applies the current home brew configuration within the seu.
 *
 * It will first uninstall removed brew software. Then it updates brew itself and any installed software.
 * Finally it will install all new added brew software.
 *
 * @author christian.fritz
 */
class ApplyBrewSoftwareTask extends DefaultTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplyBrewSoftwareTask.class);

    @Input
    Configuration brew

    @Input
    Configuration cask

    File homebrewBasePath
    SeuacDatastore datastore

    /**
     * Initializes the apply brew software task.
     */
    ApplyBrewSoftwareTask() {
        group = 'SEU-as-code'
        description = 'Install Homebrew packages into the SEU'
    }

    @Inject
    protected ExecActionFactory getExecActionFactory() {
        throw new UnsupportedOperationException();
    }

    /**
     * Apply the configuration source to the target directory. Checks with the datastore for any obsolete
     * dependencies, these will be removed. Then it finds all newly incoming deps and unpacks these to the
     * configured target directory.
     */
    @TaskAction
    void exec() {
        DatastoreProvider provider = DatastoreProviderFactory.instance.get(datastore)
        provider.init()

        brew.transitive = false
        cask.transitive = false

        // first we find all obsolete dependencies and remove associated files
        Set<String> obsoleteDeps = provider.findAllObsoleteDeps(brew.dependencies, brew.name)
        uninstallOldPackages(obsoleteDeps, brew)

        obsoleteDeps = provider.findAllObsoleteDeps(cask.dependencies, cask.name)
        uninstallOldPackages(obsoleteDeps, cask)

        updateBrewPackages()

        Set<Dependency> incomingDeps = provider.findAllIncomingDeps(brew.dependencies, brew.name)
        installNewPackages(incomingDeps, brew)

        incomingDeps = provider.findAllIncomingDeps(cask.dependencies, cask.name)
        installNewPackages(incomingDeps, cask)
    }

    /**
     * Updates homebrew it self and then updates all installed home brew software.
     */
    def updateBrewPackages() {
        LOGGER.info 'Updates brew itself'
        def update = createBrewCommand()
        update.commandLine += 'update'
        update.execute()

        LOGGER.info 'Updates all installed brew packages'
        def upgrade = createBrewCommand()
        upgrade.commandLine += 'upgrade'
        upgrade.execute()
    }

    /**
     * Removes the given brew packages.
     *
     * @param uninstallDeps a set of home brew package names to remove
     * @param configuration the configuration of the dependencies
     */
    def uninstallOldPackages(Set<String> uninstallDeps, Configuration configuration) {
        if (uninstallDeps.isEmpty()) {
            return
        }

        LOGGER.info 'Uninstall the removed brew packages: {}', uninstallDeps

        def uninstall = createBrewCommand()
        if (isCask(configuration)) {
            uninstall.commandLine += ['cask', 'uninstall']
        } else {
            uninstall.commandLine += 'uninstall'
        }
        uninstallDeps.forEach({ d ->
            uninstall.commandLine += d.split(":", 3)[1]
        })

        uninstall.execute()
    }

    /**
     * Installs the given set of dependencies using brew and store them within the seu management database using
     * the given provider.
     *
     * @param dependencies The dependencies to install.
     * @param configuration the configuration of the dependencies
     */
    def installNewPackages(Set<Dependency> dependencies, Configuration configuration) {
        LOGGER.info 'Install the new brew packages'
        dependencies.forEach({ d ->
            def installTool = createBrewCommand()
            if (isCask(configuration)) {
                installTool.commandLine += ['cask', 'install', d.name]
            } else {
                installTool.commandLine += ['install', d.name]
            }
            installTool.execute()

            LOGGER.info 'Finished installing brew package: {}', d.name
        })
    }

    protected boolean isCask(Configuration configuration) {
        "cask".equals(configuration.name)
    }

    /**
     * Create a new {@link ExecAction} for the brew installation in the current seu.
     *
     * @return A new exec action.
     */
    protected ExecAction createBrewCommand() {
        def action = getExecActionFactory().newExecAction()
        action.commandLine(Paths.get(homebrewBasePath.path, 'bin', 'brew').toString())
        action.workingDir = homebrewBasePath
        return action
    }
}
