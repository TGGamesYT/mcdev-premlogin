package dev.tggamesyt;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
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

    private final MinecraftOAuthManager manager = new MinecraftOAuthManager();
    private final MinecraftAccountsState state = MinecraftAccountsState.getInstance();
    private final Set<String> refreshingIds = ConcurrentHashMap.newKeySet();

    public MinecraftAccountsPanel(Project project) {
        this.project = project;

        panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(420, 320));

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JButton addButton = new JButton("Add Account");
        addButton.addActionListener(e -> AccountLoginFlow.addAccount(project, this::rebuild));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        south.add(addButton);

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
        if (acc.isExpired()) {
            if (refreshingIds.contains(acc.id)) return "Refreshing…";
            if (acc.canRefresh()) {
                triggerSilentRefresh(acc);
                return "Refreshing…";
            }
            return "Session expired — re-login required";
        }
        return "Microsoft account";
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
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            manager.refreshAccount(acc);
            refreshingIds.remove(acc.id);
            SwingUtilities.invokeLater(this::rebuild);
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
