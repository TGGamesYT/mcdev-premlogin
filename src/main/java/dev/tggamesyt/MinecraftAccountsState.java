package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@State(
        name = "MinecraftAccountsState",
        storages = @Storage("minecraft_accounts.xml")
)
public class MinecraftAccountsState implements PersistentStateComponent<MinecraftAccountsState> {

    public List<MinecraftAccount> accounts = new ArrayList<>();
    public String selectedAccountId;

    public static MinecraftAccountsState getInstance() {
        return ApplicationManager.getApplication()
                .getService(MinecraftAccountsState.class);
    }

    @Nullable
    @Override
    public MinecraftAccountsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull MinecraftAccountsState state) {
        this.accounts = state.accounts;
        this.selectedAccountId = state.selectedAccountId;
    }
    public void removeAccount(String accountId) {
        accounts.removeIf(a -> a.id.equals(accountId));

        if (accountId.equals(selectedAccountId)) {
            selectedAccountId = accounts.isEmpty()
                    ? null
                    : accounts.get(0).id;
        }
    }

}
