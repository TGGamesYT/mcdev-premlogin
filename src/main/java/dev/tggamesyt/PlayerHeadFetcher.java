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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fetches a player's head avatar asynchronously and caches it.
 *
 * <p>Premium accounts are looked up by UUID so the real skin is shown. Cracked accounts are looked
 * up by username, which makes the avatar service render the correct default skin (including the
 * newer non-Steve/Alex defaults) for that name's offline UUID.</p>
 *
 * <p>The cache is keyed by the stable {@link MinecraftAccount#id} (not the UUID/username, which can
 * change during a silent refresh). Once a real head has been fetched for an account it is never
 * overwritten or re-requested for the session — this prevents the avatar from being replaced by a
 * default "Steve" head if the avatar service returns a transient placeholder on a later request.</p>
 */
public final class PlayerHeadFetcher {

    private static final OkHttpClient client = new OkHttpClient();
    private static final Map<String, ImageIcon> cache = new ConcurrentHashMap<>();
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private PlayerHeadFetcher() {}

    /** Returns the already-cached head for this account/size, or null if not fetched yet. */
    public static ImageIcon getCached(MinecraftAccount account, int size) {
        return account == null ? null : cache.get(account.id + ":" + size);
    }

    /** The default "Steve" head at the given size, if already fetched. */
    public static ImageIcon getCachedSteve(int size) {
        return cache.get("STEVE:" + size);
    }

    /** Fetches the default "Steve" head (used when no account is selected). */
    public static void fetchSteve(int size, Consumer<ImageIcon> onLoaded) {
        load("MHF_Steve", "STEVE:" + size, size, onLoaded);
    }

    /**
     * Loads the head for the given account at the requested pixel size, invoking {@code onLoaded}
     * on the EDT once the image is available (immediately if already cached).
     */
    public static void fetch(MinecraftAccount account, int size, Consumer<ImageIcon> onLoaded) {
        if (account == null) return;

        String idPart = (account.type == MinecraftAccount.Type.PREMIUM && account.uuid != null && !account.uuid.isBlank())
                ? account.uuid
                : account.username;
        if (idPart == null || idPart.isBlank()) return;

        load(idPart, account.id + ":" + size, size, onLoaded);
    }

    private static void load(String idPart, String key, int size, Consumer<ImageIcon> onLoaded) {
        ImageIcon cached = cache.get(key);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        // Avoid launching duplicate requests for the same key.
        if (!inFlight.add(key)) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String url = "https://mc-heads.net/avatar/" + idPart + "/" + size;
                ImageIcon icon = null;
                // Retry a few times so a transient failure (which the service may answer with a
                // default skin) doesn't get cached as the account's permanent avatar.
                for (int attempt = 0; attempt < 3 && icon == null; attempt++) {
                    try {
                        Request request = new Request.Builder().url(url).build();
                        try (Response response = client.newCall(request).execute()) {
                            if (response.isSuccessful() && response.body() != null) {
                                byte[] bytes = response.body().bytes();
                                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                                if (img != null) icon = new ImageIcon(img);
                            }
                        }
                    } catch (Exception retry) {
                        // fall through to next attempt
                    }
                    if (icon == null) {
                        try { Thread.sleep(1000L); } catch (InterruptedException ignored) { break; }
                    }
                }
                if (icon != null) {
                    cache.put(key, icon);
                    ImageIcon finalIcon = icon;
                    SwingUtilities.invokeLater(() -> onLoaded.accept(finalIcon));
                }
            } finally {
                inFlight.remove(key);
            }
        });
    }
}
