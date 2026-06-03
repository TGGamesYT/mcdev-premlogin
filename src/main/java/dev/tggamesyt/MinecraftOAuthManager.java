package dev.tggamesyt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.json.JSONObject;

import java.awt.*;
import java.io.IOException;
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

    // Device-code / QR login uses the Microsoft Account (login.live.com) flow with the public Xbox
    // client ID instead of the Azure AD flow. The Azure device-code flow can't grant the Xbox Live
    // (XboxLive.signin) consent on a phone ("first party application ... users are not permitted to
    // consent"), but this MSA flow is exactly what Xbox/Minecraft device login is designed for and
    // has no such restriction. Tokens from here are RPS tickets, used with the "t=" prefix.
    private static final String XBOX_CLIENT_ID = "00000000402b5328";
    private static final String LIVE_DEVICECODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
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

    /** Thrown when the Azure client ID is not registered, so the interactive flow can retry with the fallback ID. */
    private static class ClientIdInvalidException extends Exception {
        ClientIdInvalidException(String message) { super(message); }
    }

    /** Thrown when an auth server is unreachable or returns a server-side error. Never triggers a browser login. */
    public static class AuthServersDownException extends Exception {
        AuthServersDownException(String message) { super(message); }
    }

    /** Thrown when neither client ID supports the device code grant (app not registered as mobile/public). */
    public static class DeviceCodeUnsupportedException extends Exception {
        DeviceCodeUnsupportedException() { super("Neither client ID supports device code flow."); }
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

    /** Persists a completed Minecraft session from the browser (Azure) flow. */
    private void saveSession(MinecraftSession mcSession, String refreshToken) {
        saveSession(mcSession, refreshToken, "azure");
    }

    /** Persists a completed Minecraft session as the selected premium account. */
    private void saveSession(MinecraftSession mcSession, String refreshToken, String authKind) {
        MinecraftAccount account = new MinecraftAccount();
        account.type = MinecraftAccount.Type.PREMIUM;
        account.username = mcSession.username;
        account.uuid = mcSession.uuid;
        account.accessToken = mcSession.accessToken;
        account.msRefreshToken = refreshToken;
        account.authKind = authKind;
        account.expiresAt = System.currentTimeMillis() + (24L * 60 * 60 * 1000); // 24h

        MinecraftAccountsState state = MinecraftAccountsState.getInstance();
        state.accounts.removeIf(a -> a.username.equals(account.username));
        state.accounts.add(account);
        state.selectedAccountId = account.id;
    }

    // ===== Device code flow (QR / "log in on another device") =====

    public static class DeviceCodeInfo {
        public String deviceCode;
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete; // may be null for MSA consumers
        public String clientId;                 // the client ID this code was issued under
        public int interval = 5;
        public int expiresIn = 900;

        /**
         * URL to encode in the QR code. Prefers the complete URL Microsoft hands back (which has the
         * code embedded); otherwise builds one with {@code ?otc=<userCode>} so scanning the QR
         * pre-fills the code and the user only has to approve.
         */
        public String qrTarget() {
            if (verificationUriComplete != null && !verificationUriComplete.isBlank()) {
                return verificationUriComplete;
            }
            if (verificationUri == null) return null;
            String sep = verificationUri.contains("?") ? "&" : "?";
            return verificationUri + sep + "otc=" + userCode;
        }
    }

    /**
     * Requests a device code for QR login via the Microsoft Account (login.live.com) flow using the
     * public Xbox client ID.
     *
     * <p>We use this instead of the Azure AD device-code endpoint on purpose: the Azure flow cannot
     * obtain the Xbox Live (XboxLive.signin) consent on a second device — the verification page
     * rejects it with "the application is a first party application ... users are not permitted to
     * consent to first party applications". The login.live.com flow with client ID
     * {@value #XBOX_CLIENT_ID} is the native Xbox/Minecraft device-login path and has no such
     * restriction, so QR login works without any Azure app configuration at all.</p>
     *
     * <p>Throws {@link AuthServersDownException} on connectivity failures.</p>
     */
    public DeviceCodeInfo startDeviceCode() throws Exception {
        return requestDeviceCode(XBOX_CLIENT_ID);
    }

    private DeviceCodeInfo requestDeviceCode(String clientId) throws Exception {
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", LIVE_SCOPE)
                .add("response_type", "device_code")
                .build();

        Request request = new Request.Builder()
                .url(LIVE_DEVICECODE_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() >= 500) {
                throw new AuthServersDownException("Device code endpoint returned HTTP " + response.code());
            }
            JSONObject json = new JSONObject(response.body().string());
            if (json.has("device_code")) {
                DeviceCodeInfo info = new DeviceCodeInfo();
                info.deviceCode = json.getString("device_code");
                info.userCode = json.getString("user_code");
                info.verificationUri = json.getString("verification_uri");
                info.verificationUriComplete = json.optString("verification_uri_complete", null);
                info.clientId = clientId; // remember which client issued this code, so polling matches
                info.interval = json.optInt("interval", 5);
                info.expiresIn = json.optInt("expires_in", 900);
                return info;
            }
            throw new RuntimeException("Device code request failed: " + json);
        } catch (IOException e) {
            throw new AuthServersDownException("Cannot reach Microsoft auth servers: " + e.getMessage());
        }
    }

    /**
     * Polls the token endpoint until the user authorizes the device code, then completes the Xbox/
     * XSTS/Minecraft chain and saves the account. Returns true on success. Stops early if
     * {@code cancelled} becomes true. Never opens a browser.
     */
    public boolean pollDeviceCodeAndSave(DeviceCodeInfo info, java.util.function.BooleanSupplier cancelled) throws Exception {
        long deadline = System.currentTimeMillis() + info.expiresIn * 1000L;
        int interval = Math.max(info.interval, 1);

        while (System.currentTimeMillis() < deadline) {
            if (cancelled.getAsBoolean()) return false;
            Thread.sleep(interval * 1000L);
            if (cancelled.getAsBoolean()) return false;

            // Poll the login.live.com token endpoint with the same client ID the code was issued under.
            String pollClientId = info.clientId != null ? info.clientId : XBOX_CLIENT_ID;
            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", pollClientId)
                    .add("device_code", info.deviceCode)
                    .build();

            Request request = new Request.Builder()
                    .url(LIVE_TOKEN_URL)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.code() >= 500) {
                    throw new AuthServersDownException("Token endpoint returned HTTP " + response.code());
                }
                JSONObject json = new JSONObject(response.body().string());

                if (json.has("access_token")) {
                    String accessToken = json.getString("access_token");
                    String refreshToken = json.optString("refresh_token", null);

                    // login.live.com tokens are RPS tickets → "t=" prefix for the Xbox exchange.
                    XboxAuthResult xboxAuth = getXboxToken(accessToken, false);
                    XboxAuthResult xstsAuth = getXstsToken(xboxAuth);
                    MinecraftSession mcSession = getMinecraftToken(xstsAuth);
                    if (mcSession == null) return false;

                    saveSession(mcSession, refreshToken, "live");
                    return true;
                }

                switch (json.optString("error", "")) {
                    case "authorization_pending":
                        break;             // keep waiting
                    case "slow_down":
                        interval += 5;     // back off
                        break;
                    default:               // expired_token, authorization_declined, bad_verification_code, ...
                        return false;
                }
            }
        }
        return false;
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
            saveSession(mcSession, msResult.refreshToken);

        } catch (ClientIdInvalidException e) {
            // Interactive login only: retry once with the fallback client ID.
            if (isClientIdGood) {
                System.out.println("Primary client ID rejected, retrying with fallback...");
                isClientIdGood = false;
                try {
                    login();
                } catch (Exception retry) {
                    retry.printStackTrace();
                }
            } else {
                System.err.println("Fallback client ID also rejected: " + e.getMessage());
            }
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
            if (response.code() >= 500) {
                throw new AuthServersDownException("Microsoft token endpoint returned HTTP " + response.code());
            }
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
            if (response.code() >= 500) {
                throw new AuthServersDownException("Microsoft token endpoint returned HTTP " + response.code());
            }
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

    /** Silently refreshes a login.live.com (device-code/QR) account using its stored refresh token. */
    private MicrosoftTokenResult refreshLiveToken(String refreshToken) throws Exception {
        System.out.println("Refreshing login.live.com token using refresh_token...");

        RequestBody body = new FormBody.Builder()
                .add("client_id", XBOX_CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("scope", LIVE_SCOPE)
                .add("refresh_token", refreshToken)
                .build();

        Request request = new Request.Builder()
                .url(LIVE_TOKEN_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() >= 500) {
                throw new AuthServersDownException("login.live.com token endpoint returned HTTP " + response.code());
            }
            String respBody = response.body().string();
            JSONObject json = new JSONObject(respBody);
            if (!json.has("access_token")) {
                throw new RuntimeException("Live token refresh failed: " + respBody);
            }
            String newRefresh = json.optString("refresh_token", null);
            return new MicrosoftTokenResult(
                    json.getString("access_token"),
                    newRefresh != null ? newRefresh : refreshToken
            );
        }
    }

    /**
     * Lightweight reachability probe for the auth endpoints. Any HTTP response (even an error
     * status) means the server is up; only a connection-level failure counts as "down".
     */
    public static boolean areAuthServersUp() {
        String[] hosts = {
                "https://login.microsoftonline.com/",
                "https://api.minecraftservices.com/"
        };
        for (String host : hosts) {
            Request req = new Request.Builder().url(host).head().build();
            try (Response ignored = client.newCall(req).execute()) {
                // reachable
            } catch (IOException e) {
                System.err.println("Auth server unreachable: " + host + " (" + e.getMessage() + ")");
                return false;
            }
        }
        return true;
    }

    /**
     * Silently re-authenticates an expired premium account using its stored Microsoft refresh token.
     * Returns true on success, false otherwise. Never opens a browser: any failure (servers down,
     * network error, invalid/expired refresh token) is handled gracefully so the user is not spammed
     * with login tabs.
     */
    public boolean refreshAccount(MinecraftAccount account) {
        if (account == null || account.msRefreshToken == null) return false;
        if (!areAuthServersUp()) {
            System.err.println("Auth servers are down; skipping silent refresh for " + account.username);
            return false;
        }
        try {
            boolean live = "live".equals(account.authKind);
            MicrosoftTokenResult msResult = live
                    ? refreshLiveToken(account.msRefreshToken)
                    : refreshMicrosoftToken(account.msRefreshToken);
            XboxAuthResult xboxAuth = getXboxToken(msResult.accessToken, !live);
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
        return getXboxToken(msToken, true);
    }

    /**
     * Exchanges a Microsoft token for an Xbox Live user token. Azure AD access tokens use the "d="
     * RpsTicket prefix; login.live.com (device-code) tokens use "t=".
     */
    private XboxAuthResult getXboxToken(String msToken, boolean azure) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("Properties", new JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", (azure ? "d=" : "t=") + msToken));
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
            if (response.code() >= 500) {
                throw new AuthServersDownException("Xbox Live endpoint returned HTTP " + response.code());
            }
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
            // A 5xx response means Minecraft's auth service itself is having problems.
            if (response.code() >= 500) {
                throw new AuthServersDownException("Minecraft auth returned HTTP " + response.code());
            }

            String body = response.body().string();
            System.out.println("Minecraft auth response: " + body);

            JSONObject json = new JSONObject(body);

            String accessToken = json.optString("access_token"); // returns "" if not present
            String errorMessage = json.optString("errorMessage", ""); // returns "" if not present

            // Bad Azure client ID — signal so the *interactive* flow can retry with the fallback ID.
            // We never trigger a browser login here, so silent refresh stays silent.
            if (errorMessage.contains("https://aka.ms/AppRegInfo")) {
                throw new ClientIdInvalidException("Azure client ID not registered. More info: https://aka.ms/AppRegInfo");
            }

            if (accessToken.isEmpty()) {
                throw new RuntimeException("Minecraft auth failed (no access token): " + body);
            }

            MinecraftSession session = new MinecraftSession();
            session.accessToken = accessToken;

            // Fetch profile
            Request profileReq = new Request.Builder()
                    .url("https://api.minecraftservices.com/minecraft/profile")
                    .addHeader("Authorization", "Bearer " + session.accessToken)
                    .build();

            try (Response profileResp = client.newCall(profileReq).execute()) {
                if (profileResp.code() >= 500) {
                    throw new AuthServersDownException("Minecraft profile returned HTTP " + profileResp.code());
                }
                String profileBody = profileResp.body().string();
                System.out.println("Profile response: " + profileBody);

                JSONObject profile = new JSONObject(profileBody);
                session.username = profile.getString("name");
                session.uuid = profile.getString("id");
            }

            return session;
        }
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
            if (response.code() >= 500) {
                throw new AuthServersDownException("XSTS endpoint returned HTTP " + response.code());
            }
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
