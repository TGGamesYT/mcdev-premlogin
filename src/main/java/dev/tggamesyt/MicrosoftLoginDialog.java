package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Dialog for adding a Microsoft account.
 *
 * <p>Normal path: shows a QR code + device code so the user can scan with a phone.
 * Closes itself once the account is saved.</p>
 *
 * <p>Degraded paths (shown as a clear message instead of raw errors):</p>
 * <ul>
 *   <li>Device code not supported by client ID → offer browser login or cracked account.</li>
 *   <li>Auth servers unreachable → tell the user and suggest cracked account.</li>
 * </ul>
 */
public class MicrosoftLoginDialog extends DialogWrapper {

    private final MinecraftOAuthManager manager;
    private final Runnable onSuccess;
    private volatile boolean cancelled = false;

    // Center panel swapped out depending on state
    private JPanel centerPanel;

    // QR flow widgets
    private final JLabel qrLabel   = new JLabel();
    private final JLabel codeLabel = new JLabel("Requesting login code…", SwingConstants.CENTER);
    private final JLabel statusLabel;

    public MicrosoftLoginDialog(@Nullable Project project, MinecraftOAuthManager manager, Runnable onSuccess) {
        super(project);
        this.manager = manager;
        this.onSuccess = onSuccess;
        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        setTitle("Add Microsoft Account");
        setOKButtonText("Login in browser instead");
        init();
        startDeviceFlow();
    }

    @Override
    protected JComponent createCenterPanel() {
        centerPanel = buildQrPanel();
        return centerPanel;
    }

    // ===== Panel builders =====

    private JPanel buildQrPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        panel.setPreferredSize(new Dimension(320, 340));

        JLabel title = new JLabel(
                "<html><div style='text-align:center'>Scan the QR code with your phone,<br>"
                        + "or open the link and enter the code:</div></html>",
                SwingConstants.CENTER);

        qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrLabel.setPreferredSize(new Dimension(240, 240));

        codeLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(codeLabel, BorderLayout.NORTH);
        bottom.add(statusLabel, BorderLayout.SOUTH);

        panel.add(title, BorderLayout.NORTH);
        panel.add(qrLabel, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /** Shown when QR / device code is unavailable but browser login might still work. */
    private JPanel buildQrUnavailablePanel() {
        return buildMessagePanel(
                "QR / device code login is not available",
                "<html><div style='text-align:center;padding:0 12px'>"
                        + "This plugin's Microsoft app registration doesn't have device code login enabled.<br><br>"
                        + "To enable it: Azure Portal &rarr; App registrations &rarr; your app &rarr;<br>"
                        + "<b>Authentication</b> &rarr; Advanced settings &rarr;<br>"
                        + "set <b>\"Allow public client flows\"</b> to <b>Yes</b>.<br><br>"
                        + "For now, click <b>Login in browser instead</b> below,<br>"
                        + "or close this dialog and add a <b>cracked account</b>."
                        + "</div></html>",
                new Color(0xFF, 0xA5, 0x00) // orange icon-ish
        );
    }

    /** Shown when auth servers are unreachable. */
    private JPanel buildServersDownPanel() {
        return buildMessagePanel(
                "Microsoft / Minecraft auth servers are unreachable",
                "<html><div style='text-align:center;padding:0 12px'>"
                        + "Could not connect to Microsoft's login servers.<br>"
                        + "This usually means they are temporarily down or your internet is offline.<br><br>"
                        + "Close this dialog and add a <b>cracked account</b> instead, or try again later."
                        + "</div></html>",
                new Color(0xE5, 0x39, 0x35) // red
        );
    }

    private static JPanel buildMessagePanel(String heading, String body, Color iconColor) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.setPreferredSize(new Dimension(380, 200));

        JLabel icon = new JLabel("⚠", SwingConstants.CENTER);
        icon.setForeground(iconColor);
        icon.setFont(icon.getFont().deriveFont(36f));

        JLabel title = new JLabel("<html><b>" + heading + "</b></html>", SwingConstants.CENTER);
        JLabel msg   = new JLabel(body, SwingConstants.CENTER);

        JPanel text = new JPanel(new BorderLayout(0, 8));
        text.add(title, BorderLayout.NORTH);
        text.add(msg,   BorderLayout.CENTER);

        panel.add(icon, BorderLayout.NORTH);
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    private void swapPanel(JPanel next) {
        centerPanel.removeAll();
        centerPanel.setLayout(new BorderLayout());
        centerPanel.add(next, BorderLayout.CENTER);
        centerPanel.revalidate();
        centerPanel.repaint();
        pack();
    }

    // ===== Device flow =====

    private void startDeviceFlow() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                MinecraftOAuthManager.DeviceCodeInfo info = manager.startDeviceCode();
                BufferedImage qr = QrCodeUtil.generate(info.qrTarget(), 240);

                SwingUtilities.invokeLater(() -> {
                    if (qr != null) qrLabel.setIcon(new ImageIcon(qr));
                    codeLabel.setText("<html><div style='text-align:center'>Go to <b>"
                            + info.verificationUri + "</b><br>"
                            + "and enter code <font size='+1'><b>" + info.userCode + "</b></font></div></html>");
                    statusLabel.setText("Waiting for you to sign in…");
                });

                boolean ok = manager.pollDeviceCodeAndSave(info, () -> cancelled);

                SwingUtilities.invokeLater(() -> {
                    if (ok) {
                        if (onSuccess != null) onSuccess.run();
                        close(OK_EXIT_CODE);
                    } else if (!cancelled) {
                        statusLabel.setText("Login failed or timed out — close and try again.");
                    }
                });

            } catch (MinecraftOAuthManager.DeviceCodeUnsupportedException e) {
                SwingUtilities.invokeLater(() -> {
                    swapPanel(buildQrUnavailablePanel());
                    // Keep the "Login in browser instead" button — it still works.
                });
            } catch (MinecraftOAuthManager.AuthServersDownException e) {
                SwingUtilities.invokeLater(() -> {
                    swapPanel(buildServersDownPanel());
                    setOKButtonText("Close");
                    getOKAction().putValue("serversDown", true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Error: " + ex.getMessage()));
            }
        });
    }

    // ===== Button actions =====

    @Override
    protected void doOKAction() {
        // If we showed the servers-down panel, OK just closes.
        if (getOKAction().getValue("serversDown") != null) {
            doCancelAction();
            return;
        }

        cancelled = true; // stop device-code polling if still running
        statusLabel.setText("Opening browser…");

        MinecraftAccountsState state = MinecraftAccountsState.getInstance();
        final int before = state.accounts.size();
        final String beforeSelected = state.selectedAccountId;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                manager.login();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Error: " + ex.getMessage()));
                return;
            }
            // Wait for handleCallback() to store the account (up to ~3 minutes).
            long deadline = System.currentTimeMillis() + 180_000L;
            while (System.currentTimeMillis() < deadline) {
                boolean changed = state.accounts.size() != before
                        || (state.selectedAccountId != null && !state.selectedAccountId.equals(beforeSelected));
                if (changed) {
                    SwingUtilities.invokeLater(() -> {
                        if (onSuccess != null) onSuccess.run();
                        close(OK_EXIT_CODE);
                    });
                    return;
                }
                try { Thread.sleep(1000L); } catch (InterruptedException ignored) { return; }
            }
        });
    }

    @Override
    public void doCancelAction() {
        cancelled = true;
        super.doCancelAction();
    }
}
