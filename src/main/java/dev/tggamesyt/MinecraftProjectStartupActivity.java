package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public class MinecraftProjectStartupActivity implements StartupActivity.DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        MinecraftOAuthConfigurable.project = project;

        // Refresh any expired tokens in the background, then wire up the run config
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            MinecraftAccountsState state = MinecraftAccountsState.getInstance();
            MinecraftOAuthManager manager = new MinecraftOAuthManager();

            // Only attempt silent refreshes if the auth servers are actually reachable.
            // This avoids hammering offline endpoints (and, historically, spawning a browser
            // login tab per account when refresh failed). refreshAccount() never opens a browser.
            if (MinecraftOAuthManager.areAuthServersUp()) {
                state.accounts.stream()
                        .filter(a -> a.isExpired() && a.canRefresh())
                        .forEach(manager::refreshAccount);
            }

            MinecraftRunConfigUpdater.updateArgs();
        });
    }
}
