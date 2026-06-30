package dev.tggamesyt;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.awt.image.BufferedImage;

/**
 * Toolbar button shown next to the Run button. Its icon is the selected account's player head.
 * Clicking it opens the Minecraft Accounts popup — the same account list / add-account UI that
 * lives in Settings → Minecraft Accounts.
 */
public class MinecraftAccountsToolbarAction extends AnAction {

    private static final int ICON_SIZE = 16;
    private static final Icon PLACEHOLDER = loadPlaceholder();

    /** The plugin icon scaled to a 16×16 toolbar icon (or a blank icon if it can't be loaded). */
    private static Icon loadPlaceholder() {
        try {
            BufferedImage img = ImageIO.read(MinecraftAccountsToolbarAction.class.getResource("/icons/icon.png"));
            if (img != null) {
                Image scaled = img.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            }
        } catch (Exception ignored) {
        }
        return new ImageIcon(new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB));
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
        if (project == null) return;

        MinecraftAccount acc = MinecraftRunConfigUpdater.selectedAccount();
        if (acc == null) {
            e.getPresentation().setIcon(PLACEHOLDER);
            e.getPresentation().setText("Minecraft accounts (none set up)");
            return;
        }

        Icon head = PlayerHeadFetcher.getCached(acc, ICON_SIZE);
        if (head == null) {
            // Kick off the fetch; the toolbar picks up the icon on a later update tick.
            PlayerHeadFetcher.fetch(acc, ICON_SIZE, icon -> {});
            head = PLACEHOLDER;
        }
        e.getPresentation().setIcon(head);
        e.getPresentation().setText("Minecraft accounts (" + acc.username + ")");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        MinecraftOAuthConfigurable.project = project;
        new MinecraftAccountsDialog(project).show();
    }
}
