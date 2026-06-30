package dev.tggamesyt;

import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;

/**
 * Hooks into the Minecraft Dev plugin's run flow: a before-run task on the "Minecraft Client"
 * configuration that runs right before the client launches.
 *
 * <ul>
 *   <li>No account configured → shows the first-launch login popup (Microsoft QR/link or offline
 *       username). If the user adds one, the launch proceeds; if they cancel, the run is aborted.</li>
 *   <li>Account configured → silently refreshes an expired token and writes the latest credentials
 *       into the run configuration before it starts.</li>
 * </ul>
 */
public class MinecraftBeforeRunTaskProvider extends BeforeRunTaskProvider<MinecraftBeforeRunTask> {

    @Override
    public Key<MinecraftBeforeRunTask> getId() {
        return MinecraftBeforeRunTask.ID;
    }

    @Override
    public String getName() {
        return "Set up / refresh Minecraft account";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    /** Auto-attach an enabled task to the Minecraft Client config (and only that one). */
    @Nullable
    @Override
    public MinecraftBeforeRunTask createTask(RunConfiguration runConfiguration) {
        if (!MinecraftRunConfigUpdater.getConfigName().equals(runConfiguration.getName())) {
            return null;
        }
        MinecraftBeforeRunTask task = new MinecraftBeforeRunTask();
        task.setEnabled(true);
        return task;
    }

    @Override
    public boolean executeTask(DataContext context, RunConfiguration configuration,
                               ExecutionEnvironment environment, MinecraftBeforeRunTask task) {
        Project project = environment.getProject();
        MinecraftOAuthConfigurable.project = project;
        MinecraftAccountsState state = MinecraftAccountsState.getInstance();

        // No account → intercept and prompt. Block until the modal dialogs finish.
        if (state.accounts.isEmpty()) {
            ApplicationManager.getApplication().invokeAndWait(
                    () -> AccountLoginFlow.showFirstLaunch(project, () -> {}));
            if (state.accounts.isEmpty()) {
                return false; // user cancelled → abort the run
            }
        }

        // Make sure something is selected, refresh an expired token, then write fresh args.
        MinecraftAccount acc = MinecraftRunConfigUpdater.selectedAccount();
        if (acc != null && acc.isExpired() && acc.canRefresh()
                && MinecraftOAuthManager.areAuthServersUp()) {
            new MinecraftOAuthManager().refreshAccount(acc);
        }
        ApplicationManager.getApplication().invokeAndWait(MinecraftRunConfigUpdater::updateArgs);
        return true;
    }
}
