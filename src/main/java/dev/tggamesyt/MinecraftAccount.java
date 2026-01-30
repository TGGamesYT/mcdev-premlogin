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

    public long expiresAt;     // millis, 0 for cracked

    public boolean isExpired() {
        return type == Type.PREMIUM && System.currentTimeMillis() > expiresAt;
    }
}
