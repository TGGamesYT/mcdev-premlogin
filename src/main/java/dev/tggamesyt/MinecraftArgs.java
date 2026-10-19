package dev.tggamesyt;

/**
 * Central definition of the Minecraft login arguments and the {@code --minecraftArgsHere}
 * placeholder.
 *
 * <p>The placeholder can be dropped into any run configuration field (program arguments, VM
 * arguments, Gradle task arguments, …). At launch time it is expanded to the selected account's
 * login flags by {@link MinecraftArgsRunConfigExtension} (Java-based configs) and
 * {@link MinecraftArgsExecutionListener} (Gradle/external-system configs). Expansion is transient —
 * the real token is never written back to the saved configuration.
 */
public final class MinecraftArgs {

    /** Literal token users add to any config/input; replaced with the real login args at run time. */
    public static final String PLACEHOLDER = "--minecraftArgsHere";

    private MinecraftArgs() {}

    /**
     * The login flags for the currently selected account, or an empty string if no account is set up.
     * Premium accounts carry uuid/accessToken/userType; cracked accounts are username-only.
     */
    public static String forSelectedAccount() {
        MinecraftAccount acc = MinecraftRunConfigUpdater.selectedAccount();
        if (acc == null) return "";
        if (acc.type == MinecraftAccount.Type.CRACKED) {
            return "--username " + acc.username;
        }
        return "--username " + acc.username
                + " --uuid " + acc.uuid
                + " --accessToken " + acc.accessToken
                + " --userType msa";
    }

    /** True if the given text contains the placeholder token. */
    public static boolean contains(String text) {
        return text != null && text.contains(PLACEHOLDER);
    }

    /**
     * Replaces every occurrence of the placeholder in {@code text} with the current account's login
     * args. If no account is set up the placeholder is simply removed. Returns {@code text}
     * unchanged when it holds no placeholder.
     */
    public static String expand(String text) {
        if (!contains(text)) return text;
        return text.replace(PLACEHOLDER, forSelectedAccount()).trim();
    }
}
