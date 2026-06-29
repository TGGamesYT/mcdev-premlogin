package dev.tggamesyt;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

/**
 * Launches the "Minecraft Client" run configuration with the selected account, prompting the user
 * to set up an account first if none exist.
 */
public final class MinecraftClientLauncher {

    private MinecraftClientLauncher() {}

    /**
     * Entry point for the toolbar button. If no account is configured, shows the first-launch login
     * popup and launches once an account has been added; otherwise launches immediately.
     */
    public static void launch(Project project) {
        if (project == null) return;

        if (MinecraftAccountsState.getInstance().accounts.isEmpty()) {
            // No accounts: ask the user to sign in (link / QR) or enter an offline username first.
            AccountLoginFlow.showFirstLaunch(project, () -> launchWithAccount(project));
            return;
        }
        launchWithAccount(project);
    }

    private static void launchWithAccount(Project project) {
        // Refresh an expired premium token before launching, then write args and run.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            MinecraftAccount acc = MinecraftRunConfigUpdater.selectedAccount();
            if (acc != null && acc.isExpired() && acc.canRefresh()
                    && MinecraftOAuthManager.areAuthServersUp()) {
                new MinecraftOAuthManager().refreshAccount(acc);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                MinecraftRunConfigUpdater.updateArgs();

                RunnerAndConfigurationSettings settings =
                        MinecraftRunConfigUpdater.findClientSettings(project);
                if (settings == null) {
                    Messages.showWarningDialog(
                            project,
                            "Couldn't find a \"" + MinecraftRunConfigUpdater.getConfigName()
                                    + "\" run configuration.\nOpen a Minecraft Development project and let it "
                                    + "generate the run configurations, then try again.",
                            "Minecraft Client Not Found");
                    return;
                }
                ProgramRunnerUtil.executeConfiguration(
                        settings, DefaultRunExecutor.getRunExecutorInstance());
            });
        });
    }
}
