package dev.tggamesyt;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Popup version of the Minecraft Accounts settings page, opened from the toolbar head button.
 * Shows the same account list + "Add Account" UI as Settings → Minecraft Accounts.
 */
public class MinecraftAccountsDialog extends DialogWrapper {

    private final MinecraftAccountsPanel accountsPanel;

    public MinecraftAccountsDialog(@Nullable Project project) {
        super(project);
        accountsPanel = new MinecraftAccountsPanel(project);
        setTitle("Minecraft Accounts");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        return accountsPanel.getComponent();
    }

    @Override
    protected Action[] createActions() {
        // A single "Close" button is enough; everything is applied immediately.
        return new Action[]{getOKAction()};
    }

    @Override
    protected void dispose() {
        accountsPanel.dispose();
        super.dispose();
    }
}
