package dev.tggamesyt;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Toolbar button shown next to the Run button. Its icon is the selected account's player head (or a
 * default Steve head when no account is selected). Clicking it opens the Minecraft Accounts popup —
 * the same account list / add-account UI that lives in Settings → Minecraft Accounts.
 */
public class MinecraftAccountsToolbarAction extends AnAction {

    private static final int ICON_SIZE = 16;
    // Neutral placeholder shown only until the real head/Steve image has been downloaded.
    private static final Icon LOADING = makeLoadingIcon();

    // Last real head we displayed. Shown while a newly-selected account's head is still loading so
    // the icon doesn't flash back to Steve when switching accounts.
    private Icon lastHead;

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        // Show in Minecraft Dev projects (a client run config exists) or when the user manually
        // marked this project as a Minecraft project in settings.
        boolean visible = project != null
                && (MinecraftRunConfigUpdater.findClientSettings(project) != null
                    || MinecraftProjectDetector.isMarked(project));
        e.getPresentation().setEnabledAndVisible(visible);
        if (!visible) return;

        // Preload every account's head so switching between them is instant (cached calls are cheap).
        for (MinecraftAccount a : MinecraftAccountsState.getInstance().accounts) {
            if (PlayerHeadFetcher.getCached(a, ICON_SIZE) == null) {
                PlayerHeadFetcher.fetch(a, ICON_SIZE, icon -> {});
            }
        }

        MinecraftAccount acc = MinecraftRunConfigUpdater.selectedAccount();
        if (acc == null) {
            e.getPresentation().setIcon(steveIcon());
            e.getPresentation().setText("Minecraft accounts (none set up)");
            return;
        }

        Icon head = PlayerHeadFetcher.getCached(acc, ICON_SIZE);
        if (head != null) {
            lastHead = head;
        } else {
            // Not loaded yet: kick off the fetch and keep showing the previous head (falling back to
            // Steve only if we've never shown one) so it never flashes to Steve mid-switch.
            PlayerHeadFetcher.fetch(acc, ICON_SIZE, icon -> {});
            head = lastHead != null ? lastHead : steveIcon();
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

    /** The Steve head once downloaded, otherwise a neutral placeholder (fetch kicked off lazily). */
    private static Icon steveIcon() {
        Icon steve = PlayerHeadFetcher.getCachedSteve(ICON_SIZE);
        if (steve != null) return steve;
        PlayerHeadFetcher.fetchSteve(ICON_SIZE, icon -> {});
        return LOADING;
    }

    private static Icon makeLoadingIcon() {
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x9E, 0x9E, 0x9E));
        g.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 4, 4);
        g.dispose();
        return new ImageIcon(img);
    }
}
