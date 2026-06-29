package dev.tggamesyt;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nullable;

/**
 * Shared account-adding flows so the Settings panel, the toolbar launch button and the
 * first-launch popup all behave identically.
 */
public final class AccountLoginFlow {

    private AccountLoginFlow() {}

    /** Generic "what kind of account?" chooser (used by the Settings "Add Account" button). */
    public static void addAccount(@Nullable Project project, Runnable onChanged) {
        int choice = Messages.showDialog(
                project,
                "What kind of account do you want to add?",
                "Add Account",
                new String[]{"Microsoft Account", "Offline (Cracked) Account", "Cancel"},
                0,
                Messages.getQuestionIcon()
        );
        if (choice == 0) {
            addMicrosoft(project, onChanged);
        } else if (choice == 1) {
            addCracked(project, onChanged);
        }
    }

    /** Opens the Microsoft (QR + browser) login dialog. */
    public static void addMicrosoft(@Nullable Project project, Runnable onChanged) {
        new MicrosoftLoginDialog(project, new MinecraftOAuthManager(), onChanged).show();
    }

    /** Prompts for an offline username and stores it as the selected account. */
    public static void addCracked(@Nullable Project project, Runnable onChanged) {
        String username = Messages.showInputDialog(
                project, "Offline (cracked) username:", "Add Offline Account", Messages.getQuestionIcon());
        if (username == null || username.isBlank()) return;

        MinecraftAccountsState state = MinecraftAccountsState.getInstance();
        MinecraftAccount acc = new MinecraftAccount();
        acc.type = MinecraftAccount.Type.CRACKED;
        acc.username = username.trim();
        state.accounts.add(acc);
        state.selectedAccountId = acc.id;
        if (onChanged != null) onChanged.run();
    }

    /**
     * First-launch popup shown when the Minecraft client is started with no accounts configured.
     * Offers Microsoft (link / QR) login or an offline username. Returns nothing; {@code onChanged}
     * fires once an account has actually been added so the caller can proceed to launch.
     */
    public static void showFirstLaunch(@Nullable Project project, Runnable onChanged) {
        int choice = Messages.showDialog(
                project,
                "You don't have a Minecraft account set up yet.\n"
                        + "Sign in with a Microsoft account (scan a QR code or use a login link),\n"
                        + "or just enter an offline username to play cracked.",
                "Set Up Your Minecraft Account",
                new String[]{"Sign in with Microsoft", "Use Offline Username", "Cancel"},
                0,
                Messages.getQuestionIcon()
        );
        if (choice == 0) {
            addMicrosoft(project, onChanged);
        } else if (choice == 1) {
            addCracked(project, onChanged);
        }
    }
}
