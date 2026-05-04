package dev.tggamesyt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.json.JSONObject;

import java.awt.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.*;

public class MinecraftOAuthManager {
    public static boolean isClientIdGood = true;
    private static final String FALLBACK_CLIENT_ID = "5e195e4a-43bc-4ae7-bc32-3e5c8ad97729";
    private static final String CLIENT_ID = "bde036fb-5483-42f3-9abf-27507bf3f510";
    private static final String REDIRECT_URI = "http://localhost:3008";
    private static HttpServer callbackServer;
    private static boolean serverStarted = false;
    private static final OkHttpClient client = new OkHttpClient();
    public String getClientId() {
        if(isClientIdGood) {
            return  CLIENT_ID;
        } else {
            return FALLBACK_CLIENT_ID;
        }
    }
    public static class MinecraftSession {
        public String username;
        public String uuid;
        public String accessToken;
    }

    private static class MicrosoftTokenResult {
        final String accessToken;
        final String refreshToken;
        MicrosoftTokenResult(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    public MinecraftSession session;

    // PKCE code verifier & challenge
    private String codeVerifier;

    public void login() throws Exception {
        // Generate PKCE
        codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        System.out.println("PKCE code verifier: " + codeVerifier);
        System.out.println("PKCE code challenge: " + codeChallenge);

        // Start callback server ONCE
        if (!serverStarted) {
            callbackServer = HttpServer.create(new InetSocketAddress(3008), 0);
            callbackServer.createContext("/", this::handleCallback);
            callbackServer.setExecutor(null);
            callbackServer.start();
            serverStarted = true;
            System.out.println("OAuth callback server started on port 3008");
        }

        // Open browser
        String scope = URLEncoder.encode("XboxLive.signin offline_access", "UTF-8");
        String url =
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize" +
                        "?client_id=" + getClientId() +
                        "&response_type=code" +
                        "&scope=" + scope +
                        "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
                        "&code_challenge=" + codeChallenge +
                        "&code_challenge_method=S256";

        Desktop.getDesktop().browse(new URI(url));
    }


    private void handleCallback(HttpExchange exchange) {
        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String code = params.get("code");

            if (code == null || code.isEmpty()) {
                // Ignore non-auth requests (favicon, reloads, probes)
                exchange.sendResponseHeaders(204, -1); // No Content
                return;
            }

            System.out.println("Auth code received: " + code);

            // Exchange auth code → Microsoft token using PKCE
            MicrosoftTokenResult msResult = getMicrosoftToken(code, codeVerifier);
            System.out.println("Microsoft token: " + msResult.accessToken);

            // Microsoft → Xbox Live
            XboxAuthResult xboxAuth = getXboxToken(msResult.accessToken);
            System.out.println("Xbox Live token: " + xboxAuth.token);
            System.out.println("Xbox UHS: " + xboxAuth.uhs);

// Xbox Live → XSTS (THIS WAS MISSING)
            XboxAuthResult xstsAuth = getXstsToken(xboxAuth);
            System.out.println("XSTS token: " + xstsAuth.token);
            System.out.println("XSTS UHS: " + xstsAuth.uhs);

// XSTS → Minecraft
            MinecraftSession mcSession = getMinecraftToken(xstsAuth);
            if (mcSession == null) return;
            this.session = mcSession;


            System.out.println("Minecraft token obtained: " + mcSession.accessToken);
            System.out.println("Username: " + mcSession.username + ", UUID: " + mcSession.uuid);

// Respond in browser
            String response = "Minecraft login successful! You can return to IntelliJ.";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            MinecraftAccount account = new MinecraftAccount();
            account.type = MinecraftAccount.Type.PREMIUM;
            account.username = mcSession.username;
            account.uuid = mcSession.uuid;
            account.accessToken = mcSession.accessToken;
            account.msRefreshToken = msResult.refreshToken;
            account.expiresAt = System.currentTimeMillis() + (24L * 60 * 60 * 1000); // 24h

            MinecraftAccountsState state = MinecraftAccountsState.getInstance();
            state.accounts.removeIf(a -> a.username.equals(account.username));
            state.accounts.add(account);
            state.selectedAccountId = account.id;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) map.put(pair[0], pair[1]);
            }
        }
        return map;
    }

    private MicrosoftTokenResult getMicrosoftToken(String code, String codeVerifier) throws Exception {
        System.out.println("Exchanging code for Microsoft token...");

        RequestBody body = new FormBody.Builder()
                .add("client_id", getClientId())
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", codeVerifier) // PKCE
                .build();

        Request request = new Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body().string();
            System.out.println("Microsoft token response: " + respBody);
            JSONObject json = new JSONObject(respBody);
            if (!json.has("access_token")) {
                throw new RuntimeException("Access token not found! Full response: " + respBody);
            }
            return new MicrosoftTokenResult(
                    json.getString("access_token"),
                    json.optString("refresh_token", null)
            );
        }
    }

    private MicrosoftTokenResult refreshMicrosoftToken(String refreshToken) throws Exception {
        System.out.println("Refreshing Microsoft token using refresh_token...");

        RequestBody body = new FormBody.Builder()
                .add("client_id", getClientId())
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("redirect_uri", REDIRECT_URI)
                .build();

        Request request = new Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body().string();
            System.out.println("MS token refresh response: " + respBody);
            JSONObject json = new JSONObject(respBody);
            if (!json.has("access_token")) {
                throw new RuntimeException("Token refresh failed: " + respBody);
            }
            // Microsoft may rotate the refresh token; fall back to the old one if not returned
            String newRefresh = json.optString("refresh_token", null);
            return new MicrosoftTokenResult(
                    json.getString("access_token"),
                    newRefresh != null ? newRefresh : refreshToken
            );
        }
    }

    /**
     * Silently re-authenticates an expired premium account using its stored Microsoft refresh token.
     * Returns true on success, false if the refresh token is missing or invalid.
     */
    public boolean refreshAccount(MinecraftAccount account) {
        if (account.msRefreshToken == null) return false;
        try {
            MicrosoftTokenResult msResult = refreshMicrosoftToken(account.msRefreshToken);
            XboxAuthResult xboxAuth = getXboxToken(msResult.accessToken);
            XboxAuthResult xstsAuth = getXstsToken(xboxAuth);
            MinecraftSession mcSession = getMinecraftToken(xstsAuth);
            if (mcSession == null) return false;

            account.accessToken = mcSession.accessToken;
            account.username = mcSession.username;
            account.uuid = mcSession.uuid;
            account.msRefreshToken = msResult.refreshToken;
            account.expiresAt = System.currentTimeMillis() + (24L * 60 * 60 * 1000);
            System.out.println("Token refreshed silently for: " + account.username);
            return true;
        } catch (Exception e) {
            System.err.println("Silent token refresh failed for " + account.username + ": " + e.getMessage());
            return false;
        }
    }

    private XboxAuthResult getXboxToken(String msToken) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("Properties", new JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", "d=" + msToken));
        payload.put("RelyingParty", "http://auth.xboxlive.com");
        payload.put("TokenType", "JWT");

        System.out.println("Sending Xbox Live auth payload:");
        System.out.println(payload.toString(2));

        Request request = new Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .post(RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            System.out.println("Xbox Live response: " + body);

            JSONObject json = new JSONObject(body);

            String xboxToken = json.getString("Token");
            String uhs = json
                    .getJSONObject("DisplayClaims")
                    .getJSONArray("xui")
                    .getJSONObject(0)
                    .getString("uhs");

            System.out.println("Xbox token extracted");
            System.out.println("UHS: " + uhs);

            return new XboxAuthResult(xboxToken, uhs);
        }
    }


    private MinecraftSession getMinecraftToken(XboxAuthResult xsts) throws Exception {
        System.out.println("Requesting Minecraft access token...");

        JSONObject payload = new JSONObject();
        payload.put(
                "identityToken",
                "XBL3.0 x=" + xsts.uhs + ";" + xsts.token
        );

        Request request = new Request.Builder()
                .url("https://api.minecraftservices.com/authentication/login_with_xbox")
                .post(RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            System.out.println("Minecraft auth response: " + body);

            JSONObject json = new JSONObject(body);

            String accessToken = json.optString("access_token"); // returns "" if not present
            String errorMessage = json.optString("errorMessage", ""); // returns "" if not present

            if (accessToken.isEmpty() || errorMessage.contains("https://aka.ms/AppRegInfo")) {
                System.out.println("The current CLIENT ID from Azure is not registered. More info: https://aka.ms/AppRegInfo");
                isClientIdGood = false;
                login();
            } else {
            MinecraftSession session = new MinecraftSession();
            session.accessToken = json.getString("access_token");

            // Fetch profile
            Request profileReq = new Request.Builder()
                    .url("https://api.minecraftservices.com/minecraft/profile")
                    .addHeader("Authorization", "Bearer " + session.accessToken)
                    .build();

            try (Response profileResp = client.newCall(profileReq).execute()) {
                String profileBody = profileResp.body().string();
                System.out.println("Profile response: " + profileBody);

                JSONObject profile = new JSONObject(profileBody);
                session.username = profile.getString("name");
                session.uuid = profile.getString("id");
            }

            return session;
            }
        }
        return null;
    }

    private XboxAuthResult getXstsToken(XboxAuthResult xboxAuth) throws Exception {
        System.out.println("Requesting XSTS token...");

        JSONObject payload = new JSONObject();
        payload.put("RelyingParty", "rp://api.minecraftservices.com/");
        payload.put("TokenType", "JWT");

        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new org.json.JSONArray().put(xboxAuth.token));

        payload.put("Properties", properties);

        Request request = new Request.Builder()
                .url("https://xsts.auth.xboxlive.com/xsts/authorize")
                .post(RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            System.out.println("XSTS response: " + body);

            JSONObject json = new JSONObject(body);

            return new XboxAuthResult(json.getString("Token"), json
                    .getJSONObject("DisplayClaims")
                    .getJSONArray("xui")
                    .getJSONObject(0)
                    .getString("uhs"));
        }
    }


    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new Random().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(verifier.getBytes("US-ASCII"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
    private static class XboxAuthResult {
        final String token;
        final String uhs;

        XboxAuthResult(String token, String uhs) {
            this.token = token;
            this.uhs = uhs;
        }
    }


}
