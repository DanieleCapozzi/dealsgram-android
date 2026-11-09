package org.telegram.dealsgram;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Client for the DealsGram API gateway.
 *
 * Authentication reuses the Telegram-signed initData of @AutoDealsProBot's mini-app, obtained with
 * messages.requestWebView: the server checks user, channel and subscription exactly as for the mini-app.
 * Every method blocks, so call it from a background queue, never from the UI thread.
 */
public class AutoDealsApi {

    public static final String API_URL = "https://api.dealsgram.app/";
    public static final String WEBAPP_URL = "https://app.autodealsbot.it";
    public static final String BOT_USERNAME = "AutoDealsProBot";

    private static final long INIT_DATA_TTL_MS = 100 * 60 * 1000L; // the server accepts 2 hours
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    /** API error codes with the DealsGramText key of their text, read in the app language when shown. */
    private static final Map<String, String> ERROR_TEXTS = new HashMap<>();
    static {
        ERROR_TEXTS.put("auth_failed", "ErrorAuth");
        ERROR_TEXTS.put("channel_not_found", "ErrorChannelNotLinked");
        ERROR_TEXTS.put("no_subscription", "ErrorNoSubscription");
        ERROR_TEXTS.put("not_allowed", "ErrorNotAllowed");
        ERROR_TEXTS.put("missing_url", "PasteLink");
        ERROR_TEXTS.put("no_format", "ErrorNoFormat");
        ERROR_TEXTS.put("rate_limited", "ErrorRateLimited");
        ERROR_TEXTS.put("external_store_offer_required", "ErrorProductNotReady");
        ERROR_TEXTS.put("free_plan_daily_limit", "ErrorFreeLimit");
        ERROR_TEXTS.put("free_plan_request_invalid", "ErrorPreviewExpired");
        ERROR_TEXTS.put("invalid_top_note", "ErrorNoteInvalid");
        ERROR_TEXTS.put("top_note_too_long", "ErrorNoteTooLong");
        ERROR_TEXTS.put("top_note_post_too_long", "ErrorNotePostTooLong");
        ERROR_TEXTS.put("invalid_custom_format", "ErrorDraftInvalid");
        ERROR_TEXTS.put("custom_format_too_long", "ErrorDraftTooLong");
        ERROR_TEXTS.put("post_format_resource_limit", "ErrorDraftResourceLimit");
        ERROR_TEXTS.put("network", "ErrorNetwork");
        ERROR_TEXTS.put("add_curated_failed", "ErrorAddCurated");
        ERROR_TEXTS.put("offer_not_found", "ErrorOfferNotFound");
        ERROR_TEXTS.put("autoposting_v2_publishing_window", "ErrorPublishingWindow");
        ERROR_TEXTS.put("queue_update_failed", "ErrorQueueUpdate");
        ERROR_TEXTS.put("skip_failed", "ErrorSkip");
        ERROR_TEXTS.put("already_posted", "ErrorAlreadyPosted");
        ERROR_TEXTS.put("invalid_schedule", "ErrorInvalidTime");
        ERROR_TEXTS.put("too_few_offers", "ErrorTooFewOffers");
        ERROR_TEXTS.put("too_many_offers", "ErrorTooManyOffers");
        ERROR_TEXTS.put("add_curated_post_failed", "ErrorMultiNotQueued");
    }

    public static class AutoDealsException extends Exception {
        public final String code;
        public final String text;

        public AutoDealsException(String code, String message) {
            super(TextUtils.isEmpty(message) ? code : message);
            this.code = code;
            String known = ERROR_TEXTS.containsKey(code) ? DealsGramText.get(ERROR_TEXTS.get(code)) : null;
            if (known == null && message != null && message.contains("DEAL_PRICE_MISSING")) {
                // Raw scraper error: the store page shows no price (sold out, or no offer right now).
                known = DealsGramText.get("ErrorNoPrice");
            }
            this.text = known != null ? known : (TextUtils.isEmpty(message) ? code : message);
        }
    }

    private static final Map<Integer, AutoDealsApi> instances = new HashMap<>();

    public static synchronized AutoDealsApi getInstance(int account) {
        AutoDealsApi api = instances.get(account);
        if (api == null) {
            api = new AutoDealsApi(account);
            instances.put(account, api);
        }
        return api;
    }

    private final int account;
    private String initData;
    private long initDataAt;

    private AutoDealsApi(int account) {
        this.account = account;
    }

    /**
     * Where a request comes from, sent as the informational "client" block: the screen that makes it and how the
     * user got there.
     */
    public static final class Origin {
        public static final Origin BACKGROUND = new Origin("background", "none");

        final String screen;
        final String entry;

        public Origin(String screen, String entry) {
            this.screen = screen;
            this.entry = entry;
        }
    }

    /** A read without a screen of its own, such as the list of linked channels. */
    public Object call(String action, JSONObject params) throws AutoDealsException {
        return call(action, params, Origin.BACKGROUND, true);
    }

    /** Calls an action; returns the "data" field (JSONObject, JSONArray or null). */
    public Object call(String action, JSONObject params, Origin origin) throws AutoDealsException {
        return call(action, params, origin, true);
    }

    private Object call(String action, JSONObject params, Origin origin, boolean retry) throws AutoDealsException {
        JSONObject body;
        try {
            JSONObject payload = new JSONObject();
            payload.put("initData", getInitData());
            payload.put("action", action);
            payload.put("params", params != null ? params : new JSONObject());
            payload.put("client", client(origin));
            body = new JSONObject(post(payload.toString()));
        } catch (AutoDealsException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e("AutoDeals " + action, e);
            throw new AutoDealsException("network", null);
        }
        if (body.optBoolean("ok")) {
            return body.opt("data");
        }
        String code = body.optString("error", "unknown");
        if ("auth_failed".equals(code) && retry) {
            // initData expired or rejected: one more attempt with fresh data.
            synchronized (this) {
                initData = null;
            }
            return call(action, params, origin, false);
        }
        throw new AutoDealsException(code, body.optString("message", null));
    }

    // --- Usage statistics ---------------------------------------------------

    private static final String PREF_INSTALL_ID = "install_id";
    private static final String PREF_LAST_PING = "last_ping_at";
    private static final long PING_EVERY_MS = 12 * 60 * 60 * 1000L;

    /** A random id of this installation, created at first use; it never contains Telegram data. */
    static synchronized String installId() {
        android.content.SharedPreferences prefs = AutoDealsPublisher.prefs();
        String id = prefs.getString(PREF_INSTALL_ID, null);
        if (TextUtils.isEmpty(id)) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(PREF_INSTALL_ID, id).apply();
        }
        return id;
    }

    /** The installed versionCode (Telegram build, DealsGram build, ABI digit). */
    private static long buildNumber() {
        try {
            android.content.Context context = ApplicationLoader.applicationContext;
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONObject client(Origin origin) throws Exception {
        JSONObject client = new JSONObject();
        client.put("app", "dealsgram");
        client.put("platform", "android");
        client.put("version", BuildVars.BUILD_VERSION_STRING);
        client.put("build", buildNumber());
        client.put("install_id", installId());
        client.put("screen", origin != null ? origin.screen : Origin.BACKGROUND.screen);
        client.put("entry", origin != null ? origin.entry : Origin.BACKGROUND.entry);
        return client;
    }

    /**
     * Anonymous daily ping (dealsgram_ping) at app start: installs and active installations, also of users who
     * never use AutoDeals. No initData and no Telegram data. Every 12 hours and on the first start of each day
     * (Europe/Rome, the server's calendar), so every active day is counted; a failed ping is retried at the next start.
     */
    public static void pingIfDue() {
        android.content.SharedPreferences prefs = AutoDealsPublisher.prefs();
        long last = prefs.getLong(PREF_LAST_PING, 0);
        long now = System.currentTimeMillis();
        if (now - last < PING_EVERY_MS && serverDay(last).equals(serverDay(now))) {
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject params = new JSONObject();
                params.put("install_id", installId());
                params.put("version", BuildVars.BUILD_VERSION_STRING);
                params.put("build", buildNumber());
                params.put("os", android.os.Build.VERSION.SDK_INT);
                params.put("language", org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo() != null
                    ? org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "");
                params.put("country", country());
                params.put("autodeals", AutoDealsPublisher.hasLinkedChannels());
                JSONObject payload = new JSONObject();
                payload.put("action", "dealsgram_ping");
                payload.put("params", params);
                if (new JSONObject(post(payload.toString())).optBoolean("ok")) {
                    prefs.edit().putLong(PREF_LAST_PING, System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                FileLog.e("DealsGram ping", e);
            }
        });
    }

    private static String serverDay(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Europe/Rome"));
        return format.format(new java.util.Date(millis));
    }

    /** The SIM's country, else the system's; empty when unknown. */
    private static String country() {
        try {
            android.telephony.TelephonyManager telephony = (android.telephony.TelephonyManager)
                ApplicationLoader.applicationContext.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            String sim = telephony != null ? telephony.getSimCountryIso() : null;
            if (!TextUtils.isEmpty(sim)) {
                return sim.toUpperCase(java.util.Locale.US);
            }
        } catch (Exception ignore) {
        }
        String system = java.util.Locale.getDefault().getCountry();
        return system != null ? system.toUpperCase(java.util.Locale.US) : "";
    }

    private static String post(String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "DealsGram-Android");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new String(readAll(in, Integer.MAX_VALUE), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** The product photo, or null when it is missing or too large. */
    public static byte[] download(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            if (connection.getResponseCode() >= 400) {
                return null;
            }
            return readAll(connection.getInputStream(), MAX_IMAGE_BYTES);
        } catch (Exception e) {
            FileLog.e("AutoDeals image", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > limit) {
                    return null;
                }
            }
            return out.toByteArray();
        }
    }

    // --- Telegram initData -------------------------------------------------

    private synchronized String getInitData() throws AutoDealsException {
        if (initData != null && SystemClock.elapsedRealtime() - initDataAt < INIT_DATA_TTL_MS) {
            return initData;
        }
        initData = requestInitData();
        initDataAt = SystemClock.elapsedRealtime();
        return initData;
    }

    /** Asks Telegram for the bot's Web App and extracts tgWebAppData, the signed initData. */
    private String requestInitData() throws AutoDealsException {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User bot = resolveBot(controller);
        TLRPC.TL_messages_requestWebView req = new TLRPC.TL_messages_requestWebView();
        req.peer = controller.getInputPeer(bot.id);
        req.bot = controller.getInputUser(bot);
        req.url = WEBAPP_URL;
        req.flags |= 2; // url present
        req.platform = "android";

        TLObject result = callTelegram(req);
        String webUrl = result instanceof TLRPC.TL_webViewResultUrl ? ((TLRPC.TL_webViewResultUrl) result).url : null;
        if (TextUtils.isEmpty(webUrl)) {
            throw new AutoDealsException("auth_failed", null);
        }
        // The fragment is "tgWebAppData=<urlencoded initData>&tgWebAppVersion=...": decoding it once
        // gives initData exactly as the mini-app receives it (Telegram.WebApp.initData).
        String fragment = Uri.parse(webUrl).getEncodedFragment();
        if (fragment != null) {
            for (String part : fragment.split("&")) {
                if (part.startsWith("tgWebAppData=")) {
                    return Uri.decode(part.substring("tgWebAppData=".length()));
                }
            }
        }
        throw new AutoDealsException("auth_failed", null);
    }

    private TLRPC.User resolveBot(MessagesController controller) throws AutoDealsException {
        TLObject cached = controller.getUserOrChat(BOT_USERNAME);
        if (cached instanceof TLRPC.User) {
            return (TLRPC.User) cached;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BOT_USERNAME;
        TLObject result = callTelegram(req);
        if (!(result instanceof TLRPC.TL_contacts_resolvedPeer) || ((TLRPC.TL_contacts_resolvedPeer) result).users.isEmpty()) {
            throw new AutoDealsException("auth_failed", null);
        }
        TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) result;
        controller.putUsers(resolved.users, false);
        return resolved.users.get(0);
    }

    private TLObject callTelegram(TLObject req) throws AutoDealsException {
        CountDownLatch done = new CountDownLatch(1);
        TLObject[] response = new TLObject[1];
        TLRPC.TL_error[] error = new TLRPC.TL_error[1];
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            response[0] = res;
            error[0] = err;
            done.countDown();
        });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) {
                throw new AutoDealsException("network", "Telegram non risponde.");
            }
        } catch (InterruptedException e) {
            throw new AutoDealsException("network", "Telegram non risponde.");
        }
        if (error[0] != null) {
            FileLog.e("AutoDeals Telegram " + req.getClass().getSimpleName() + ": " + error[0].text);
            throw new AutoDealsException("auth_failed", null);
        }
        return response[0];
    }
}
