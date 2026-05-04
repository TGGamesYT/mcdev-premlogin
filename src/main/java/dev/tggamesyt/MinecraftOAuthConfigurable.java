package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MinecraftOAuthConfigurable implements Configurable {

    private final JPanel panel;
    private final JComboBox<String> accountBox;
    private final JTextField runConfigNameField;
    private boolean updatingAccountBox = false;
    private Timer refreshTimer;

    private final MinecraftOAuthManager manager = new MinecraftOAuthManager();
    private final MinecraftAccountsState state = MinecraftAccountsState.getInstance();
    private final Set<String> refreshingIds = ConcurrentHashMap.newKeySet();
    static Project project = null;

    public MinecraftOAuthConfigurable(Project project) {
        MinecraftOAuthConfigurable.project = project;
        panel = new JPanel(new BorderLayout(5,5));

        // --- Account dropdown ---
        accountBox = new JComboBox<>();
        accountBox.addActionListener(e -> {
            if (updatingAccountBox) return; // ignore events fired during refresh
            int idx = accountBox.getSelectedIndex();
            if (idx >= 0 && idx < state.accounts.size()) {
                state.selectedAccountId = state.accounts.get(idx).id;
                MinecraftRunConfigUpdater.updateArgs(); // update args when selection changes
            }
        });

        // --- Run configuration name input ---
        JLabel runConfigLabel = new JLabel("Run configuration name:");
        runConfigNameField = new JTextField("Minecraft Client", 30);
        runConfigNameField.addActionListener(e -> updateRunConfigName());

        JPanel runConfigPanel = new JPanel();
        runConfigPanel.setLayout(new BoxLayout(runConfigPanel, BoxLayout.Y_AXIS));
        runConfigPanel.add(runConfigLabel);

        JPanel fieldWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fieldWrapper.add(runConfigNameField);
        runConfigPanel.add(fieldWrapper);

        // --- Buttons panel ---
        JPanel buttons = getButtonsPanel();

        // --- Assemble main panel ---
        panel.add(accountBox, BorderLayout.NORTH);
        panel.add(runConfigPanel, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        refreshAccounts();

        // Refresh account status every 60 seconds
        refreshTimer = new Timer(60_000, e -> refreshAccounts());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
    }

    private @NotNull JPanel getButtonsPanel() {
        JButton loginButton = new JButton("Add Microsoft Account");
        loginButton.addActionListener(e -> {
            try {
                manager.login();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            refreshAccounts();
        });

        JButton addCrackedButton = getCrackedButton();
        JButton deleteButton = getDeleteButton();
        JButton refreshButton = new JButton("Refresh Accounts");
        refreshButton.addActionListener(e -> refreshAccounts());

        JPanel buttons = new JPanel();
        buttons.add(loginButton);
        buttons.add(addCrackedButton);
        buttons.add(deleteButton);
        buttons.add(refreshButton);
        return buttons;
    }

    private @NotNull JButton getDeleteButton() {
        JButton deleteButton = new JButton("Delete Selected Account");
        deleteButton.addActionListener(e -> {
            MinecraftAccount selected = getSelectedAccount();
            if (selected == null) return;

            int result = Messages.showYesNoDialog(
                    "Remove account \"" + selected.username + "\"?",
                    "Delete Minecraft Account",
                    Messages.getQuestionIcon()
            );

            if (result != Messages.YES) return;

            state.removeAccount(selected.id);
            refreshAccounts();
        });
        return deleteButton;
    }

    private @NotNull JButton getCrackedButton() {
        JButton addCrackedButton = new JButton("Add Cracked Account");
        addCrackedButton.addActionListener(e -> {
            String username = JOptionPane.showInputDialog("Cracked username:");
            if (username == null || username.isBlank()) return;

            MinecraftAccount acc = new MinecraftAccount();
            acc.type = MinecraftAccount.Type.CRACKED;
            acc.username = username;

            state.accounts.add(acc);
            state.selectedAccountId = acc.id;
            refreshAccounts();
        });
        return addCrackedButton;
    }

    private void refreshAccounts() {
        String selectedId = state.selectedAccountId;

        updatingAccountBox = true;
        try {
            accountBox.removeAllItems();
            int selectedIndex = -1;
            for (int i = 0; i < state.accounts.size(); i++) {
                MinecraftAccount acc = state.accounts.get(i);
                String label;
                if (acc.isExpired()) {
                    if (acc.canRefresh() && !refreshingIds.contains(acc.id)) {
                        // Kick off a background silent refresh
                        refreshingIds.add(acc.id);
                        MinecraftAccount finalAcc = acc;
                        ApplicationManager.getApplication().executeOnPooledThread(() -> {
                            manager.refreshAccount(finalAcc);
                            refreshingIds.remove(finalAcc.id);
                            SwingUtilities.invokeLater(this::refreshAccounts);
                        });
                        label = acc.username + " (Refreshing...)";
                    } else if (refreshingIds.contains(acc.id)) {
                        label = acc.username + " (Refreshing...)";
                    } else {
                        label = acc.username + " (EXPIRED - RE-LOGIN REQUIRED)";
                    }
                } else {
                    label = acc.username + " (" + acc.type + ")";
                }
                accountBox.addItem(label);
                if (acc.id.equals(selectedId)) selectedIndex = i;
            }

            if (selectedIndex >= 0) {
                accountBox.setSelectedIndex(selectedIndex);
            }
        } finally {
            updatingAccountBox = false;
        }

        MinecraftRunConfigUpdater.updateArgs();
    }

    private MinecraftAccount getSelectedAccount() {
        int index = accountBox.getSelectedIndex();
        if (index < 0 || index >= state.accounts.size()) return null;
        return state.accounts.get(index);
    }

    /** Called whenever the user changes the run config name field */
    private void updateRunConfigName() {
        String name = runConfigNameField.getText().trim();
        if (!name.isEmpty()) {
            MinecraftRunConfigUpdater.setConfigName(name);
            MinecraftRunConfigUpdater.updateArgs();
        }
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Minecraft Accounts";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {}

    @Override
    public void disposeUIResources() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }
}
