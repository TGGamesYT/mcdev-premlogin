package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;

/**
 * Detects whether an opened/imported project is a Minecraft project directly from its files, and —
 * for Gradle-based ones that were opened without importing (e.g. "open folder" or cloning a repo) —
 * triggers a Gradle link + refresh.
 *
 * <p>Why this is needed: the Minecraft Dev plugin only recognises a project as Minecraft once its
 * {@code MinecraftFacetDetector} finds a known Minecraft library on a module's classpath, and those
 * libraries only appear after the Gradle project has been imported. If you merely open the folder or
 * clone the repo without importing Gradle, no libraries are attached, so Minecraft Dev (and this
 * plugin along with it) never activate. Forcing the Gradle link makes the facet detector run on the
 * resulting {@code rootsChanged}, so the project ends up treated exactly like an existing Minecraft
 * Dev project.
 */
public final class MinecraftProjectDetector {

    private MinecraftProjectDetector() {}

    // Resource/marker files that identify a Minecraft project regardless of build-system import state.
    private static final String[] MARKER_FILES = {
            "fabric.mod.json",
            "quilt.mod.json",
            "src/main/resources/fabric.mod.json",
            "src/main/resources/quilt.mod.json",
            "src/main/resources/META-INF/mods.toml",
            "src/main/resources/META-INF/neoforge.mods.toml",
            "src/main/resources/META-INF/sponge_plugins.json",
            "src/main/resources/plugin.yml",
            "src/main/resources/paper-plugin.yml",
            "src/main/resources/bungee.yml",
            "src/main/resources/velocity-plugin.json",
    };

    // Gradle build-script files: scanned for Minecraft tokens, and used to decide whether we can
    // trigger a Gradle import.
    private static final String[] GRADLE_FILES = {
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    };

    // Lower-cased tokens whose presence in a Gradle build script marks a Minecraft project.
    private static final String[] BUILD_TOKENS = {
            "fabric-loom", "fabricmc", "net.minecraftforge", "minecraftforge",
            "net.neoforged", "neoforged", "neoforge", "architectury",
            "spongepowered", "sponge", "paperweight", "io.papermc", "papermc",
            "bukkit", "spigotmc", "spigot", "velocitypowered", "bungeecord",
            "net.minecraft", "com.mojang", "loom",
    };

    /** True if the project looks like a Minecraft project (any platform Minecraft Dev supports). */
    public static boolean isMinecraftProject(Project project) {
        VirtualFile base = baseDir(project);
        if (base == null) return false;

        for (String marker : MARKER_FILES) {
            if (base.findFileByRelativePath(marker) != null) return true;
        }
        for (String buildFile : GRADLE_FILES) {
            VirtualFile f = base.findChild(buildFile);
            if (f != null && containsMinecraftToken(f)) return true;
        }
        return false;
    }

    /**
     * If this is a Gradle-based Minecraft project that has not been linked/imported yet, link it so
     * Minecraft Dev's facet detection kicks in. Best-effort and fully guarded: any missing API or
     * failure degrades to a no-op (the IDE's own "import Gradle project" prompt still applies).
     */
    public static void ensureImported(Project project) {
        try {
            if (!isMinecraftProject(project)) return;

            VirtualFile base = baseDir(project);
            if (base == null) return;

            boolean hasGradle = false;
            for (String g : GRADLE_FILES) {
                if (base.findChild(g) != null) {
                    hasGradle = true;
                    break;
                }
            }
            if (!hasGradle) return; // nothing we can import

            if (isGradleLinked(project)) return; // already imported — detector will handle it

            final String path = base.getPath();
            ApplicationManager.getApplication().invokeLater(() -> linkGradleProject(project, path));
        } catch (Throwable ignored) {
            // Detection must never break project opening.
        }
    }

    private static VirtualFile baseDir(Project project) {
        if (project == null) return null;
        try {
            return ProjectUtil.guessProjectDir(project);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsMinecraftToken(VirtualFile file) {
        try {
            if (file.getLength() > 1_000_000) return false; // don't slurp absurdly large files
            String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            for (String token : BUILD_TOKENS) {
                if (text.contains(token)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Reflectively checks {@code GradleSettings.getInstance(project).getLinkedProjectsSettings()}. */
    private static boolean isGradleLinked(Project project) {
        try {
            Class<?> gradleSettings = Class.forName("org.jetbrains.plugins.gradle.settings.GradleSettings");
            Object instance = gradleSettings.getMethod("getInstance", Project.class).invoke(null, project);
            Object linked = gradleSettings.getMethod("getLinkedProjectsSettings").invoke(instance);
            if (linked instanceof Collection) {
                return !((Collection<?>) linked).isEmpty();
            }
        } catch (Throwable ignored) {
            // If we can't tell, assume linked so we don't force an unwanted import.
            return true;
        }
        return false;
    }

    /** Reflectively calls Gradle's {@code linkAndRefreshGradleProject(projectPath, project)}. */
    private static void linkGradleProject(Project project, String projectPath) {
        try {
            Class<?> importUtil =
                    Class.forName("org.jetbrains.plugins.gradle.service.project.open.GradleProjectImportUtil");
            importUtil.getMethod("linkAndRefreshGradleProject", String.class, Project.class)
                    .invoke(null, projectPath, project);
        } catch (Throwable ignored) {
            // API unavailable in this IDE build: fall back to the IDE's own Gradle import prompt.
        }
    }
}
