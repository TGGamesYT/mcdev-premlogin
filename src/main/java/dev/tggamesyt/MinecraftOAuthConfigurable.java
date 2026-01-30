package dev.tggamesyt;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class MinecraftOAuthConfigurable implements Configurable {

    private final JPanel panel;
    private final JComboBox<String> accountBox;
    private final JTextField runConfigDirField;

    private final MinecraftOAuthManager manager = new MinecraftOAuthManager();
    private final MinecraftAccountsState state = MinecraftAccountsState.getInstance();
    static Project project = null;

    public MinecraftOAuthConfigurable(Project project) {
        MinecraftOAuthConfigurable.project = project;
        panel = new JPanel(new BorderLayout(5,5));

        // --- Account dropdown ---
        accountBox = new JComboBox<>();
        accountBox.addActionListener(e -> {
            int idx = accountBox.getSelectedIndex();
            if (idx >= 0 && idx < state.accounts.size()) {
                state.selectedAccountId = state.accounts.get(idx).id;
                MinecraftRunConfigUpdater.updateArgs(); // update args when selection changes
            }
        });

        // --- RunConfigurations directory input ---
        JLabel runConfigLabel = new JLabel("<html>If you move the runConfigurations directory,<br>update this field to match the new location,<br>or Minecraft_Client.xml will not be found.</html>");
        runConfigDirField = new JTextField(getDefaultRunConfigDir(), 40);
        runConfigDirField.addActionListener(e -> updateRunConfigFile());

        // Use BoxLayout to prevent stretching vertically
        JPanel runConfigPanel = new JPanel();
        runConfigPanel.setLayout(new BoxLayout(runConfigPanel, BoxLayout.Y_AXIS));
        runConfigPanel.add(runConfigLabel);

        JPanel fieldWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fieldWrapper.add(runConfigDirField);
        runConfigPanel.add(fieldWrapper);

        // --- Buttons panel ---
        JPanel buttons = getButtonsPanel();

        // --- Assemble main panel ---
        panel.add(accountBox, BorderLayout.NORTH);
        panel.add(runConfigPanel, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        refreshAccounts();
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
        MinecraftRunConfigUpdater.updateArgs(); // auto-update args

        // remember currently selected account
        String selectedId = state.selectedAccountId;

        accountBox.removeAllItems();
        int selectedIndex = -1;
        for (int i = 0; i < state.accounts.size(); i++) {
            MinecraftAccount acc = state.accounts.get(i);
            accountBox.addItem(acc.username + " (" + acc.type + ")");
            if (acc.id.equals(selectedId)) selectedIndex = i;
        }

        if (selectedIndex >= 0) {
            accountBox.setSelectedIndex(selectedIndex);
        }
    }

    private MinecraftAccount getSelectedAccount() {
        int index = accountBox.getSelectedIndex();
        if (index < 0 || index >= state.accounts.size()) return null;
        return state.accounts.get(index);
    }

    /** Returns the default runConfigurations directory for the current project */
    private String getDefaultRunConfigDir() {
        return project.getBasePath() + "/.idea/runConfigurations";
    }

    /** Called whenever the user changes the runConfigDirField */
    private void updateRunConfigFile() {
        String dir = runConfigDirField.getText().trim();
        if (!dir.isEmpty()) {
            MinecraftRunConfigUpdater.setConfigFile(dir + "/Minecraft_Client.xml");
            MinecraftRunConfigUpdater.updateArgs(); // immediately update VM args
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
}
