package dev.tggamesyt;

import com.intellij.execution.BeforeRunTask;
import com.intellij.openapi.util.Key;

/**
 * Marker before-run task attached to the "Minecraft Client" run configuration. Its presence makes
 * {@link MinecraftBeforeRunTaskProvider#executeTask} run right before the client launches, so the
 * account check / login prompt can intercept the actual Run button.
 */
public class MinecraftBeforeRunTask extends BeforeRunTask<MinecraftBeforeRunTask> {

    static final Key<MinecraftBeforeRunTask> ID = Key.create("Minecraft.EnsureAccountBeforeRun");

    public MinecraftBeforeRunTask() {
        super(ID);
    }
}
