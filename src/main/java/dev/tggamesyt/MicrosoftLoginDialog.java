package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Dialog for adding a Microsoft account. Shows a QR code + device code (scan with a phone) and also
 * offers the classic in-browser login as a fallback. Closes itself once an account is added.
 */
public class MicrosoftLoginDialog extends DialogWrapper {

    private final MinecraftOAuthManager manager;
    private final Runnable onSuccess;

    private volatile boolean cancelled = false;

    private final JLabel qrLabel = new JLabel();
    private final JLabel codeLabel = new JLabel("Requesting login code…", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);

    public MicrosoftLoginDialog(@Nullable Project project, MinecraftOAuthManager manager, Runnable onSuccess) {
        super(project);
        this.manager = manager;
        this.onSuccess = onSuccess;
        setTitle("Add Microsoft Account");
        setOKButtonText("Login in browser instead");
        init();
        startDeviceFlow();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("Scan the QR code with your phone, or open the link and enter the code:",
                SwingConstants.CENTER);

        qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrLabel.setPreferredSize(new Dimension(240, 240));

        codeLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(codeLabel, BorderLayout.NORTH);
        bottom.add(statusLabel, BorderLayout.SOUTH);

        panel.add(title, BorderLayout.NORTH);
        panel.add(qrLabel, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private void startDeviceFlow() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                MinecraftOAuthManager.DeviceCodeInfo info = manager.startDeviceCode();
                BufferedImage qr = QrCodeUtil.generate(info.qrTarget(), 240);

                SwingUtilities.invokeLater(() -> {
                    if (qr != null) qrLabel.setIcon(new ImageIcon(qr));
                    codeLabel.setText("<html><div style='text-align:center'>Go to <b>" + info.verificationUri
                            + "</b><br>and enter code <font size='+1'><b>" + info.userCode + "</b></font></div></html>");
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
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Error: " + ex.getMessage()));
            }
        });
    }

    /** OK button = classic in-browser login. Watches for the new account, then closes. */
    @Override
    protected void doOKAction() {
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
            while (System.currentTimeMillis() < deadline && !cancelled) {
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
