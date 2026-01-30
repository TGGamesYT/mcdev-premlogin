package dev.tggamesyt;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Pattern;

public class MinecraftRunConfigUpdater {

    // Pattern to remove old Minecraft args (username, uuid, accessToken, userType)
    private static final Pattern MC_ARGS_PATTERN = Pattern.compile(
            "(--username\\s+\\S+)|" +
                    "(--uuid\\s+\\S+)|" +
                    "(--accessToken\\s+\\S+)|" +
                    "(--userType\\s+\\S+)"
    );

    // The current Minecraft_Client.xml file to update
    private static File configFile = new File(
            MinecraftOAuthConfigurable.project.getBasePath(),
            ".idea/runConfigurations/Minecraft_Client.xml"
    );

    /** Sets a new Minecraft_Client.xml file (called from settings panel) */
    public static void setConfigFile(String path) {
        configFile = new File(path);
    }

    /**
     * Updates the Minecraft Client run configuration with the currently selected account.
     * Automatically removes old Minecraft args to prevent duplicates.
     */
    public static void updateArgs() {
        if (configFile == null || !configFile.exists()) {
            System.err.println("Minecraft run configuration file not found: " + configFile);
            return;
        }

        try {
            MinecraftAccountsState state = MinecraftAccountsState.getInstance();
            MinecraftAccount acc = state.accounts.stream()
                    .filter(a -> a.id.equals(state.selectedAccountId))
                    .findFirst()
                    .orElse(null);

            if (acc == null) return; // nothing selected

            SAXBuilder builder = new SAXBuilder();
            Document doc = builder.build(configFile);
            Element root = doc.getRootElement(); // <component>

            for (Element config : root.getChildren("configuration")) {
                String name = config.getAttributeValue("name");
                if (!"Minecraft Client".equals(name)) continue;

                for (Element option : config.getChildren("option")) {
                    if (!"PROGRAM_PARAMETERS".equals(option.getAttributeValue("name"))) continue;

                    String args = option.getAttributeValue("value", "");
                    // Remove old MC-specific args
                    args = MC_ARGS_PATTERN.matcher(args).replaceAll("").trim();

                    // Append new args for selected account
                    if (acc.type == MinecraftAccount.Type.CRACKED) {
                        args += " --username " + acc.username;
                    } else {
                        if (acc.isExpired()) throw new RuntimeException("Minecraft session expired. Re-login required.");
                        args += " --username " + acc.username;
                        args += " --uuid " + acc.uuid;
                        args += " --accessToken " + acc.accessToken;
                        args += " --userType msa";
                    }

                    option.setAttribute("value", args.trim());
                }
            }

            // Save XML back
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                XMLOutputter xmlOut = new XMLOutputter();
                xmlOut.setFormat(Format.getPrettyFormat());
                xmlOut.output(doc, fos);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
