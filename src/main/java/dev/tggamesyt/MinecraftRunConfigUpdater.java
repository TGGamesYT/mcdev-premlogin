package dev.tggamesyt;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

public class MinecraftRunConfigUpdater {

    private static final Pattern MC_ARGS_PATTERN = Pattern.compile(
            "(--username\\s+\\S+)|" +
                    "(--uuid\\s+\\S+)|" +
                    "(--accessToken\\s+\\S+)|" +
                    "(--userType\\s+\\S+)"
    );

    private static String configName = "Minecraft Client";

    public static void setConfigName(String name) {
        configName = name;
    }

    /**
     * Updates the Minecraft Client run configuration with the currently selected account
     * using IntelliJ's RunManager API for reliable persistence.
     */
    public static void updateArgs() {
        Project project = MinecraftOAuthConfigurable.project;
        if (project == null) return;

        try {
            MinecraftAccountsState state = MinecraftAccountsState.getInstance();
            MinecraftAccount acc = state.accounts.stream()
                    .filter(a -> a.id.equals(state.selectedAccountId))
                    .findFirst()
                    .orElse(null);

            if (acc == null) return;

            RunManager runManager = RunManager.getInstance(project);

            for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
                RunConfiguration config = settings.getConfiguration();
                if (!configName.equals(config.getName())) continue;

                // Get current program parameters via reflection,
                // since the minecraft-dev plugin's config type may not be on our compile classpath
                String args = "";
                Method getter = findMethod(config.getClass(), "getProgramParameters");
                if (getter != null) {
                    getter.setAccessible(true);
                    Object result = getter.invoke(config);
                    if (result instanceof String) {
                        args = (String) result;
                    }
                }

                // Remove old MC-specific args
                args = MC_ARGS_PATTERN.matcher(args).replaceAll("").trim();

                // Append new args for selected account
                if (acc.type == MinecraftAccount.Type.CRACKED) {
                    args += " --username " + acc.username;
                } else {
                    // Use the stored token even if marked expired; a background refresh will update it shortly
                    args += " --username " + acc.username;
                    args += " --uuid " + acc.uuid;
                    args += " --accessToken " + acc.accessToken;
                    args += " --userType msa";
                }

                args = args.trim();

                // Set the updated parameters via reflection
                Method setter = findMethod(config.getClass(), "setProgramParameters", String.class);
                if (setter != null) {
                    setter.setAccessible(true);
                    setter.invoke(config, args);
                }

                // Tell RunManager to persist the change
                runManager.makeStable(settings);
                return;
            }

            System.err.println("Run configuration '" + configName + "' not found.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Walks the class hierarchy to find a method by name and parameter types. */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                for (Class<?> iface : clazz.getInterfaces()) {
                    try {
                        return iface.getDeclaredMethod(name, paramTypes);
                    } catch (NoSuchMethodException ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
