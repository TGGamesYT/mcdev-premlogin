package dev.tggamesyt;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reusable Minecraft accounts management UI: a scrollable list of accounts (player head, name,
 * status, per-account ⋮ menu) plus a single "Add Account" button. Used both by the Settings page
 * ({@link MinecraftOAuthConfigurable}) and the toolbar head-button popup
 * ({@link MinecraftAccountsDialog}).
 */
public class MinecraftAccountsPanel implements Disposable {

    private static final int HEAD_SIZE = 32;
    private static final Icon PLACEHOLDER_HEAD = makePlaceholderHead();

    private final Project project;
    private final JPanel panel;
    private final JPanel listPanel;
    private Timer refreshTimer;

    // Don't retry a silent refresh more often than this per account (avoids hammering + a
    // perpetual "Refreshing…" when a refresh keeps failing).
    private static final long REFRESH_BACKOFF_MS = 2 * 60 * 1000;

    private final MinecraftOAuthManager manager = new MinecraftOAuthManager();
    private final MinecraftAccountsState state = MinecraftAccountsState.getInstance();
    private final Set<String> refreshingIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastRefreshAttempt = new ConcurrentHashMap<>();

    public MinecraftAccountsPanel(Project project, boolean showRunButton) {
        this.project = project;

        panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(560, 340));

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JButton addButton = new JButton("Add Account");
        addButton.addActionListener(e -> AccountLoginFlow.addAccount(project, this::rebuild));

        // WrapLayout so the controls flow onto a second line when the panel is narrow, instead of
        // being clipped at the right edge.
        JPanel south = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        south.add(addButton);

        // Fallback launcher (Settings only): run the Gradle "runClient" task directly, logged in.
        // Shown only when the project actually exposes that task.
        if (showRunButton && GradleRunClient.isAvailable(project)) {
            JButton runButton = new JButton("Run Minecraft (gradle runClient)");
            runButton.addActionListener(e -> runClient());
            south.add(runButton);
        }

        // Settings-only controls, in the same button row.
        if (showRunButton) {
            south.add(buildMarkToggle());
            south.add(buildGradleSupportToggle());
            south.add(buildArgsInfoButton());
        }

        panel.add(scroll, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        rebuild();

        // Refresh account status periodically (also drives silent re-auth of expired accounts).
        refreshTimer = new Timer(60_000, e -> rebuild());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
    }

    public JComponent getComponent() {
        return panel;
    }

    /**
     * "Mark this project as a Minecraft project" toggle. It reflects the current state (auto-detected
     * or already shown) and lets the user force it on or off for this project.
     */
    private JComponent buildMarkToggle() {
        JCheckBox mark = new JCheckBox("Mark as Minecraft project");
        mark.setToolTipText("Shows the account head button next to the run configurations. "
                + "Reflects auto-detection; toggle to force it on or off for this project.");
        mark.setEnabled(project != null);
        mark.setSelected(MinecraftProjectDetector.shouldShowAccountButton(project));
        mark.addActionListener(e -> MinecraftProjectDetector.setOverride(project, mark.isSelected()));
        return mark;
    }

    /**
     * Toggle for the global Gradle init script that expands {@link MinecraftArgs#PLACEHOLDER} in
     * Gradle run configurations and the terminal.
     */
    private JComponent buildGradleSupportToggle() {
        JCheckBox toggle = new JCheckBox("Gradle/terminal support");
        toggle.setToolTipText("Installs a global Gradle init script so " + MinecraftArgs.PLACEHOLDER
                + " works in Gradle run configs and the terminal.");
        toggle.setSelected(MinecraftGradleInitScript.isEnabled());
        toggle.addActionListener(e -> {
            if (toggle.isSelected()) {
                boolean ok = MinecraftGradleInitScript.enable();
                if (!ok) {
                    toggle.setSelected(false);
                    Messages.showErrorDialog(project,
                            "Couldn't install the Gradle init script in your Gradle user home.",
                            "Minecraft OAuth");
                }
            } else {
                MinecraftGradleInitScript.disable();
            }
        });
        return toggle;
    }

    /** A button that opens a popup explaining the {@code --minecraftArgsHere} placeholder. */
    private JComponent buildArgsInfoButton() {
        JButton info = new JButton(MinecraftArgs.PLACEHOLDER + " ?");
        info.setToolTipText("What is " + MinecraftArgs.PLACEHOLDER + "?");
        info.addActionListener(e -> showArgsInfoDialog());
        return info;
    }

    /** Shows the placeholder explanation at its natural size (no scroll area). */
    private void showArgsInfoDialog() {
        DialogWrapper dialog = new DialogWrapper(project, false) {
            {
                setTitle(MinecraftArgs.PLACEHOLDER);
                init();
            }

            @Override
            protected JComponent createCenterPanel() {
                JLabel label = new JLabel("<html><div style='width:420px;'>"
                        + "Add <b>" + MinecraftArgs.PLACEHOLDER + "</b> to any run configuration field "
                        + "or command (program arguments, VM arguments, Gradle task arguments, …). "
                        + "When it runs, it is replaced with the selected account's login arguments "
                        + "(<code>--username … --uuid … --accessToken … --userType msa</code>). "
                        + "The real token is only used for that run and is never saved back into the "
                        + "configuration.<br><br>"
                        + "For a Gradle <b>runClient</b> task, use:<br>"
                        + "<code>--args=\"" + MinecraftArgs.PLACEHOLDER + "\"</code><br><br>"
                        + "To make it work in Gradle run configurations and the terminal, enable "
                        + "<b>Gradle/terminal support</b> — that installs a small Gradle init "
                        + "script in your Gradle user home and writes the current login args (incl. "
                        + "the access token) to an owner-only file there. Java-based run "
                        + "configurations work without it."
                        + "</div></html>");
                label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                return label;
            }

            @Override
            protected Action[] createActions() {
                return new Action[]{getOKAction()};
            }
        };
        dialog.show();
    }

    /**
     * Fallback launcher: runs the Gradle {@code runClient} task directly (not the Minecraft Dev run
     * configuration), passing the selected account's login args. Refreshes an expired token first.
     */
    private void runClient() {
        if (project == null) return;
        MinecraftAccount acc = MinecraftRunConfigUpdater.selectedAccount();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (acc != null && acc.isExpired() && acc.canRefresh()
                    && MinecraftOAuthManager.areAuthServersUp()) {
                manager.refreshAccount(acc);
            }
            ApplicationManager.getApplication().invokeLater(() -> GradleRunClient.run(project, acc));
        });
    }

    // ===== List rendering =====

    public void rebuild() {
        listPanel.removeAll();

        if (state.accounts.isEmpty()) {
            JLabel empty = new JLabel("No accounts yet — click \"Add Account\" to get started.");
            empty.setForeground(UIManager.getColor("Label.disabledForeground"));
            empty.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));
            listPanel.add(empty);
        } else {
            for (MinecraftAccount acc : state.accounts) {
                listPanel.add(createRow(acc));
            }
        }
        listPanel.add(Box.createVerticalGlue());

        listPanel.revalidate();
        listPanel.repaint();

        MinecraftRunConfigUpdater.updateArgs();
    }

    private JComponent createRow(MinecraftAccount acc) {
        boolean selected = acc.id.equals(state.selectedAccountId);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        Color bg = selected ? UIManager.getColor("List.selectionBackground") : UIManager.getColor("List.background");
        Color fg = selected ? UIManager.getColor("List.selectionForeground") : UIManager.getColor("List.foreground");
        row.setBackground(bg);

        // Head avatar
        JLabel head = new JLabel(PLACEHOLDER_HEAD);
        head.setPreferredSize(new Dimension(HEAD_SIZE, HEAD_SIZE));
        PlayerHeadFetcher.fetch(acc, HEAD_SIZE, head::setIcon);
        row.add(head, BorderLayout.WEST);

        // Name + status
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel name = new JLabel(acc.username);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        if (fg != null) name.setForeground(fg);

        JLabel status = new JLabel(statusText(acc));
        status.setForeground(selected ? fg : UIManager.getColor("Label.disabledForeground"));
        status.setFont(status.getFont().deriveFont(status.getFont().getSize2D() - 1f));

        text.add(name);
        text.add(status);
        row.add(text, BorderLayout.CENTER);

        // 3-dot menu
        JButton menu = new JButton("⋮"); // vertical ellipsis
        menu.setMargin(new Insets(0, 6, 0, 6));
        menu.setFocusable(false);
        menu.setContentAreaFilled(false);
        menu.setBorderPainted(false);
        menu.setToolTipText("Account actions");
        menu.addActionListener(e -> showRowMenu(acc, menu));
        row.add(menu, BorderLayout.EAST);

        // Click row (anywhere but the menu button) to select it
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                state.selectedAccountId = acc.id;
                rebuild();
            }
        });

        return row;
    }

    private String statusText(MinecraftAccount acc) {
        if (acc.type == MinecraftAccount.Type.CRACKED) {
            return "Cracked (offline) account";
        }
        // The stored session can no longer be refreshed silently — the user must sign in again.
        if (acc.needsReLogin) return "Microsoft account (re-login needed)";
        // Only show "Refreshing…" while a refresh is genuinely in flight.
        if (refreshingIds.contains(acc.id)) return "Refreshing…";

        if (acc.isExpired()) {
            if (acc.canRefresh()) {
                // Kick off a quiet background refresh (rate-limited). Don't advertise "Refreshing…"
                // here: the saved session usually still works, and if the refresh keeps failing we
                // must not get stuck on that label forever.
                maybeTriggerSilentRefresh(acc);
                return "Microsoft account";
            }
            return "Session expired — re-login required";
        }
        return "Microsoft account";
    }

    /** Starts a silent refresh only if one isn't running and we haven't tried too recently. */
    private void maybeTriggerSilentRefresh(MinecraftAccount acc) {
        Long last = lastRefreshAttempt.get(acc.id);
        if (last != null && System.currentTimeMillis() - last < REFRESH_BACKOFF_MS) return;
        triggerSilentRefresh(acc);
    }

    private void showRowMenu(MinecraftAccount acc, Component anchor) {
        JPopupMenu popup = new JPopupMenu();

        if (acc.type == MinecraftAccount.Type.PREMIUM && acc.canRefresh()) {
            JMenuItem refresh = new JMenuItem("Refresh");
            refresh.addActionListener(e -> {
                triggerSilentRefresh(acc);
                rebuild();
            });
            popup.add(refresh);
        }

        JMenuItem delete = new JMenuItem("Delete");
        delete.addActionListener(e -> {
            int result = Messages.showYesNoDialog(
                    project,
                    "Remove account \"" + acc.username + "\"?",
                    "Delete Minecraft Account",
                    Messages.getQuestionIcon()
            );
            if (result != Messages.YES) return;
            state.removeAccount(acc.id);
            rebuild();
        });
        popup.add(delete);

        popup.show(anchor, 0, anchor.getHeight());
    }

    private void triggerSilentRefresh(MinecraftAccount acc) {
        if (!refreshingIds.add(acc.id)) return; // already in progress
        lastRefreshAttempt.put(acc.id, System.currentTimeMillis());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                manager.refreshAccount(acc);
            } finally {
                refreshingIds.remove(acc.id);
                SwingUtilities.invokeLater(this::rebuild);
            }
        });
    }

    private static Icon makePlaceholderHead() {
        BufferedImage img = new BufferedImage(HEAD_SIZE, HEAD_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x9E, 0x9E, 0x9E));
        g.fillRoundRect(0, 0, HEAD_SIZE, HEAD_SIZE, 6, 6);
        g.dispose();
        return new ImageIcon(img);
    }

    @Override
    public void dispose() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }
}
