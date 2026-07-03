package dev.tggamesyt;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.model.task.TaskData;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;

import java.util.Collection;
import java.util.Collections;

/**
 * Runs the Gradle {@code runClient} task directly (independent of the Minecraft Dev "Minecraft
 * Client" run configuration), injecting the selected account's login arguments. Used as a fallback
 * so the game can be launched even in projects without a generated run configuration.
 */
public final class GradleRunClient {

    private static final ProjectSystemId GRADLE = new ProjectSystemId("GRADLE");
    private static final String TASK = "runClient";

    private GradleRunClient() {}

    /** True if this project exposes a Gradle {@code runClient} task. */
    public static boolean isAvailable(Project project) {
        return project != null && findRunClientPath(project) != null;
    }

    /** External project path of the (sub)project that declares runClient, or null if none. */
    private static String findRunClientPath(Project project) {
        try {
            Collection<ExternalProjectInfo> infos =
                    ProjectDataManager.getInstance().getExternalProjectsData(project, GRADLE);
            for (ExternalProjectInfo info : infos) {
                DataNode<?> structure = info.getExternalProjectStructure();
                if (structure == null) continue;
                for (DataNode<TaskData> node : ExternalSystemApiUtil.findAllRecursively(structure, ProjectKeys.TASK)) {
                    if (TASK.equals(node.getData().getName())) {
                        return node.getData().getLinkedExternalProjectPath();
                    }
                }
            }
        } catch (Exception ignored) {
            // External-system data not ready / API surprise: treat as unavailable.
        }
        return null;
    }

    /** Launches {@code gradle runClient}, passing the account's login args to the game. */
    public static void run(Project project, MinecraftAccount acc) {
        if (project == null) return;
        String path = findRunClientPath(project);
        if (path == null) path = project.getBasePath();
        if (path == null) return;

        ExternalSystemTaskExecutionSettings settings = new ExternalSystemTaskExecutionSettings();
        settings.setExternalProjectPath(path);
        settings.setTaskNames(Collections.singletonList(TASK));
        settings.setExternalSystemIdString(GRADLE.getId());
        settings.setExecutionName("runClient" + (acc != null ? " (" + acc.username + ")" : ""));
        if (acc != null) {
            // Pass MC login as JavaExec-style program args to the run task.
            settings.setScriptParameters("--args=\"" + loginArgs(acc) + "\"");
        }

        ExternalSystemUtil.runTask(settings, DefaultRunExecutor.EXECUTOR_ID, project, GRADLE);
    }

    private static String loginArgs(MinecraftAccount acc) {
        StringBuilder sb = new StringBuilder("--username ").append(acc.username);
        if (acc.type == MinecraftAccount.Type.PREMIUM) {
            sb.append(" --uuid ").append(acc.uuid)
              .append(" --accessToken ").append(acc.accessToken)
              .append(" --userType msa");
        }
        return sb.toString();
    }
}
