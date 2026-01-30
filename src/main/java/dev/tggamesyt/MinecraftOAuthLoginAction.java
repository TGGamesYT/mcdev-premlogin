package dev.tggamesyt;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;

public class MinecraftOAuthLoginAction extends AnAction {
    MinecraftOAuthManager manager = new MinecraftOAuthManager();
    @Override
    public void actionPerformed(AnActionEvent e) {
        try {
            manager.login();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        Messages.showMessageDialog("Login flow started. Check your browser.", "Minecraft OAuth", Messages.getInformationIcon());
    }
}
