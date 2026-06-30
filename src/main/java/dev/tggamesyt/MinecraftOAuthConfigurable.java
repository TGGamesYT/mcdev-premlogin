package dev.tggamesyt;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class MinecraftOAuthConfigurable implements Configurable {

    static Project project = null;

    private MinecraftAccountsPanel accountsPanel;

    public MinecraftOAuthConfigurable(Project project) {
        MinecraftOAuthConfigurable.project = project;
    }

    // ===== Configurable =====

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Minecraft Accounts";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        accountsPanel = new MinecraftAccountsPanel(project);
        return accountsPanel.getComponent();
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {}

    @Override
    public void disposeUIResources() {
        if (accountsPanel != null) {
            accountsPanel.dispose();
            accountsPanel = null;
        }
    }
}
