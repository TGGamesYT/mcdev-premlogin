package dev.tggamesyt;

import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.openapi.options.SettingsEditor;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Expands the {@link MinecraftArgs#PLACEHOLDER} token in any Java-based run configuration's program
 * and VM arguments right before it launches. The expansion is applied to the per-run
 * {@link JavaParameters}, so the real login args (including the access token) are never persisted
 * back into the saved configuration.
 *
 * <p>Gradle/external-system run configurations don't go through {@code JavaParameters}; those are
 * handled by {@link MinecraftArgsExecutionListener}.
 */
public class MinecraftArgsRunConfigExtension extends RunConfigurationExtension {

    @Override
    public boolean isApplicableFor(@NotNull RunConfigurationBase<?> configuration) {
        return true;
    }

    @Override
    public <T extends RunConfigurationBase<?>> void updateJavaParameters(
            @NotNull T configuration, @NotNull JavaParameters params, @Nullable RunnerSettings runnerSettings) {
        expandPlaceholder(params.getProgramParametersList());
        expandPlaceholder(params.getVMParametersList());
    }

    /** Rebuilds the list with the placeholder replaced by the current account's login args. */
    private static void expandPlaceholder(ParametersList list) {
        try {
            String joined = list.getParametersString();
            if (!MinecraftArgs.contains(joined)) return;
            String expanded = MinecraftArgs.expand(joined);
            list.clearAll();
            list.addParametersString(expanded);
        } catch (Throwable ignored) {
            // Never block a launch because of placeholder expansion.
        }
    }

    @Override
    protected void readExternal(@NotNull RunConfigurationBase runConfiguration, @NotNull Element element) {
    }

    @Nullable
    @Override
    protected String getEditorTitle() {
        return null;
    }

    @Nullable
    @Override
    protected <P extends RunConfigurationBase<?>> SettingsEditor<P> createEditor(@NotNull P configuration) {
        return null;
    }
}
