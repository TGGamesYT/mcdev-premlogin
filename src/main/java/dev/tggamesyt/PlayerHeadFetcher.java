package dev.tggamesyt;

import com.intellij.openapi.application.ApplicationManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fetches a player's head avatar asynchronously and caches it.
 *
 * <p>Premium accounts are looked up by UUID so the real skin is shown. Cracked accounts are looked
 * up by username, which makes the avatar service render the correct default skin (including the
 * newer non-Steve/Alex defaults) for that name's offline UUID.</p>
 */
public final class PlayerHeadFetcher {

    private static final OkHttpClient client = new OkHttpClient();
    private static final Map<String, ImageIcon> cache = new ConcurrentHashMap<>();

    private PlayerHeadFetcher() {}

    /**
     * Loads the head for the given account at the requested pixel size, invoking {@code onLoaded}
     * on the EDT once the image is available (immediately if already cached).
     */
    public static void fetch(MinecraftAccount account, int size, Consumer<ImageIcon> onLoaded) {
        String idPart = (account.type == MinecraftAccount.Type.PREMIUM && account.uuid != null && !account.uuid.isBlank())
                ? account.uuid
                : account.username;
        if (idPart == null || idPart.isBlank()) return;

        String key = idPart + ":" + size;
        ImageIcon cached = cache.get(key);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String url = "https://mc-heads.net/avatar/" + idPart + "/" + size;
            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    byte[] bytes = response.body().bytes();
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (img == null) return;
                    ImageIcon icon = new ImageIcon(img);
                    cache.put(key, icon);
                    SwingUtilities.invokeLater(() -> onLoaded.accept(icon));
                }
            } catch (Exception ignored) {
                // Offline or service down: leave the placeholder in place.
            }
        });
    }
}
