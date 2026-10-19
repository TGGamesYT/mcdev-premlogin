package dev.tggamesyt;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Expands the {@link MinecraftArgs#PLACEHOLDER} token in Gradle / external-system run
 * configurations. These launch through the external-system task runner rather than
 * {@code JavaParameters}, so the token lives in the task's script parameters
 * ({@code --args="--minecraftArgsHere"} for a {@code runClient} task, for example).
 *
 * <p>The placeholder is expanded just before the run and the original value is restored once the
 * run finishes, so the real login args (including the access token) are never written back to the
 * saved configuration.
 */
public class MinecraftArgsExecutionListener implements ExecutionListener {

    // Original script-parameter strings to restore after the run, keyed by the settings object.
    private static final Map<ExternalSystemTaskExecutionSettings, String> originals =
            Collections.synchronizedMap(new IdentityHashMap<>());

    @Override
    public void processStartScheduled(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
        // When the global Gradle init script is enabled it handles Gradle runs (and the terminal),
        // so we don't also touch the persisted settings here.
        if (MinecraftGradleInitScript.isEnabled()) return;

        ExternalSystemTaskExecutionSettings settings = settingsOf(env);
        if (settings == null) return;

        String params = settings.getScriptParameters();
        if (!MinecraftArgs.contains(params)) return;

        originals.put(settings, params);
        settings.setScriptParameters(MinecraftArgs.expand(params));
    }

    @Override
    public void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
        restore(env);
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                                  @NotNull ProcessHandler handler, int exitCode) {
        restore(env);
    }

    private static void restore(ExecutionEnvironment env) {
        ExternalSystemTaskExecutionSettings settings = settingsOf(env);
        if (settings == null) return;
        String original = originals.remove(settings);
        if (original != null) {
            settings.setScriptParameters(original);
        }
    }

    private static ExternalSystemTaskExecutionSettings settingsOf(ExecutionEnvironment env) {
        try {
            RunProfile profile = env.getRunProfile();
            if (profile instanceof ExternalSystemRunConfiguration) {
                return ((ExternalSystemRunConfiguration) profile).getSettings();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
