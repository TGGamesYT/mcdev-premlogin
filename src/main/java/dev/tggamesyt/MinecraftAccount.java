package dev.tggamesyt;

import java.util.UUID;

public class MinecraftAccount {

    public enum Type {
        PREMIUM,
        CRACKED
    }

    public String id = UUID.randomUUID().toString();

    public Type type;

    public String username;
    public String uuid;        // null for cracked
    public String accessToken; // null for cracked

    public long expiresAt;      // millis, 0 for cracked
    public String msRefreshToken; // Microsoft refresh token; null for cracked

    /**
     * Which OAuth flow produced this account, so silent refresh uses the right endpoint and Xbox
     * ticket prefix. "azure" = browser auth-code flow (login.microsoftonline.com, RpsTicket d=);
     * "live" = device-code/QR flow (login.live.com, RpsTicket t=). Defaults to azure for accounts
     * saved before this field existed.
     */
    public String authKind = "azure";

    public boolean isExpired() {
        return type == Type.PREMIUM && System.currentTimeMillis() > expiresAt;
    }

    public boolean canRefresh() {
        return type == Type.PREMIUM && msRefreshToken != null;
    }
}
