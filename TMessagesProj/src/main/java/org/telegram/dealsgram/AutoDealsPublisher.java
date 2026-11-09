package org.telegram.dealsgram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry points for publishing an offer to AutoDeals from a chat, plus the request payloads.
 * The flow itself is {@link AutoDealsSheet}; it ports the exteraGram plugin (exteragram-plugin/autodeals.py).
 */
public class AutoDealsPublisher {

    private static final Pattern BARE_LINK = Pattern.compile("\\s*https?://\\S+\\s*");
    private static final Pattern ANY_LINK = Pattern.compile("https?://\\S+");
    /** A message made only of two or more links: a multi post. */
    private static final Pattern ONLY_LINKS = Pattern.compile("\\s*https?://\\S+(\\s+https?://\\S+)+\\s*");
    private static final long CHANNELS_TTL_MS = 10 * 60 * 1000L;

    private static final Map<Integer, Map<Long, JSONObject>> channelsByAccount = new HashMap<>();
    /** Offerta terminata from a post's menu: an action with no window of its own. */
    private static final AutoDealsApi.Origin MESSAGE = new AutoDealsApi.Origin("message", "message_menu");
    private static final Map<Integer, Long> channelsAt = new HashMap<>();

    /** Android dialog_id (-1234567890) → AutoDeals / Bot API channel id (-1001234567890). */
    public static long botApiChannelId(long dialogId) {
        return dialogId < 0 ? -1000000000000L + dialogId : 0;
    }

    // --- Entry points --------------------------------------------------------

    /**
     * Called before a text message is sent. A message that is only a link, in a channel linked to
     * AutoDeals, opens the publishing sheet instead of being sent. Returns true when it was taken over.
     */
    public static boolean interceptSend(BaseFragment fragment, long dialogId, CharSequence text, Runnable clearInput) {
        if (fragment == null || fragment.getParentActivity() == null || text == null) {
            return false;
        }
        boolean several = ONLY_LINKS.matcher(text).matches();
        if (!several && !BARE_LINK.matcher(text).matches()) {
            return false;
        }
        Map<Long, JSONObject> channels = cachedChannels(UserConfig.selectedAccount);
        if (channels == null) {
            refreshChannelsAsync(UserConfig.selectedAccount);
            return false;
        }
        JSONObject channel = channels.get(botApiChannelId(dialogId));
        if (channel == null) {
            return false;
        }
        if (several) {
            // The links stay in the input until the post is created, as for a single link.
            openMulti(fragment, dialogId, channel, findLinks(text), clearInput, "send_link");
            return true;
        }
        if (AutoDealsDraft.fieldMode() && hasSubscription(channel) && fragment instanceof org.telegram.ui.ChatActivity) {
            // Impostazioni → AutoDeals → "Modifica nel campo di scrittura": the post goes into the field.
            AutoDealsDraft.start((org.telegram.ui.ChatActivity) fragment, dialogId, channel, text.toString().trim());
            return true;
        }
        new AutoDealsSheet(fragment, dialogId, text.toString().trim(), text.toString(), clearInput).from("send_link").show();
        return true;
    }

    /** "Post multiplo" from the channel menu: links pasted in the sheet, pre-filled from the clipboard. */
    public static void startMulti(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        int account = UserConfig.selectedAccount;
        Map<Long, JSONObject> channels = cachedChannels(account);
        JSONObject channel = channels != null ? channels.get(botApiChannelId(dialogId)) : null;
        if (channel != null) {
            openMulti(fragment, dialogId, channel, null, null, "chat_menu");
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject found = null;
            String error = null;
            try {
                for (JSONObject c : loadChannels(account)) {
                    if (c.optLong("id") == botApiChannelId(dialogId)) {
                        found = c;
                    }
                }
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            }
            JSONObject result = found;
            String errorText = error;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (errorText != null) {
                    org.telegram.ui.Components.BulletinFactory.of(fragment).createErrorBulletin(errorText).show();
                } else if (result == null) {
                    AutoDealsPlanSheet.showConnect(fragment.getParentActivity(), fragment.getResourceProvider());
                } else {
                    openMulti(fragment, dialogId, result, null, null, "chat_menu");
                }
            });
        });
    }

    private static void openMulti(BaseFragment fragment, long dialogId, JSONObject channel, List<String> links, Runnable onCreated, String entry) {
        if (!canMultiPost(channel)) {
            AutoDealsPlanSheet.showFeature(fragment.getParentActivity(), fragment.getResourceProvider(), AutoDealsPlanSheet.FEATURE_MULTI, channel.optLong("id"));
            return;
        }
        new AutoDealsMultiSheet(fragment.getParentActivity(), fragment.getResourceProvider(), dialogId, botApiChannelId(dialogId),
            new ArrayList<>(), hasAutopostingQueue(channel), links, () -> {}, done -> {
                if (onCreated != null) {
                    onCreated.run();
                }
                org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, done).show();
            }).from(entry).show();
    }

    /** Every web link in a text, in order, without repeats. */
    public static List<String> findLinks(CharSequence text) {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        if (text != null) {
            Matcher m = ANY_LINK.matcher(text);
            while (m.find()) {
                links.add(m.group());
            }
        }
        return new ArrayList<>(links);
    }

    /**
     * A page of the @AutoDealsProBot mini-app (app.autodealsbot.it), e.g. "/channel/-100…/autoposting-v2/timetables",
     * in Telegram's own mini-app sheet: signed in, with Telegram's theme. Used for settings DealsGram does not
     * rebuild natively; the mini-app routes by its URL.
     */
    public static void openMiniApp(BaseFragment fragment, String path) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        int account = UserConfig.selectedAccount;
        org.telegram.messenger.MessagesController controller = org.telegram.messenger.MessagesController.getInstance(account);
        org.telegram.tgnet.TLObject cached = controller.getUserOrChat(AutoDealsApi.BOT_USERNAME);
        if (cached instanceof org.telegram.tgnet.TLRPC.User) {
            showMiniApp(fragment, account, (org.telegram.tgnet.TLRPC.User) cached, path);
            return;
        }
        org.telegram.tgnet.TLRPC.TL_contacts_resolveUsername req = new org.telegram.tgnet.TLRPC.TL_contacts_resolveUsername();
        req.username = AutoDealsApi.BOT_USERNAME;
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof org.telegram.tgnet.TLRPC.TL_contacts_resolvedPeer && !((org.telegram.tgnet.TLRPC.TL_contacts_resolvedPeer) response).users.isEmpty()) {
                org.telegram.tgnet.TLRPC.TL_contacts_resolvedPeer resolved = (org.telegram.tgnet.TLRPC.TL_contacts_resolvedPeer) response;
                controller.putUsers(resolved.users, false);
                showMiniApp(fragment, account, resolved.users.get(0), path);
            } else if (fragment.getParentActivity() != null) {
                org.telegram.ui.Components.BulletinFactory.of(fragment).createErrorBulletin(DealsGramText.get("ErrorNetwork")).show();
            }
        }));
    }

    private static void showMiniApp(BaseFragment fragment, int account, org.telegram.tgnet.TLRPC.User bot, String path) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        String url = AutoDealsApi.WEBAPP_URL + (path.startsWith("/") ? path : "/" + path);
        org.telegram.ui.bots.WebViewRequestProps props = org.telegram.ui.bots.WebViewRequestProps.of(account, bot.id, bot.id,
            "AutoDeals", url, org.telegram.ui.bots.BotWebViewAttachedSheet.TYPE_WEB_VIEW_BUTTON, 0, 0, false, null, false, null, bot, 0, false, false);
        org.telegram.ui.bots.BotWebViewSheet sheet = new org.telegram.ui.bots.BotWebViewSheet(fragment.getParentActivity(), fragment.getResourceProvider());
        sheet.setDefaultFullsize(true);
        sheet.setNeedsContext(true);
        sheet.setParentActivity(fragment.getParentActivity());
        sheet.requestWebView(fragment, props);
        sheet.show();
    }

    /** Warms the channel cache, so the first pasted link in a channel is recognised. */
    public static void prefetch(long dialogId) {
        if (dialogId < 0 && cachedChannels(UserConfig.selectedAccount) == null) {
            refreshChannelsAsync(UserConfig.selectedAccount);
        }
    }

    /** "Pubblica offerta" from a menu: the sheet asks for the link, pre-filled from the clipboard. */
    public static void start(BaseFragment fragment, long dialogId) {
        if (fragment != null && fragment.getParentActivity() != null) {
            new AutoDealsSheet(fragment, dialogId, null, null, null).from("chat_menu").show();
        }
    }

    // --- From a post, also in other people's channels -------------------------

    private static final java.util.Set<String> TELEGRAM_HOSTS = new java.util.HashSet<>(java.util.Arrays.asList(
        "t.me", "www.t.me", "telegram.me", "telegram.dog"));

    /**
     * Offer links of a post: links hidden behind text, plain links in the text, link buttons.
     * Telegram links (channels, bots) are left out, as in the exteraGram plugin.
     */
    public static List<String> messageLinks(org.telegram.messenger.MessageObject message) {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        if (message == null || message.messageOwner == null) {
            return new ArrayList<>();
        }
        org.telegram.tgnet.TLRPC.Message owner = message.messageOwner;
        String text = owner.message != null ? owner.message : "";
        if (owner.entities != null) {
            for (org.telegram.tgnet.TLRPC.MessageEntity entity : owner.entities) {
                if (entity instanceof org.telegram.tgnet.TLRPC.TL_messageEntityTextUrl) {
                    links.add(entity.url);
                } else if (entity instanceof org.telegram.tgnet.TLRPC.TL_messageEntityUrl
                    && entity.offset >= 0 && entity.offset + entity.length <= text.length()) {
                    links.add(text.substring(entity.offset, entity.offset + entity.length));
                }
            }
        }
        Matcher m = ANY_LINK.matcher(text);
        while (m.find()) {
            links.add(m.group());
        }
        if (owner.reply_markup instanceof org.telegram.tgnet.TLRPC.TL_replyInlineMarkup) {
            for (org.telegram.tgnet.tl.TL_keyboard.KeyboardInlineButtonRow row : ((org.telegram.tgnet.TLRPC.TL_replyInlineMarkup) owner.reply_markup).rows) {
                for (Object button : row.buttons) {
                    if (button instanceof org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonProto) {
                        links.add(((org.telegram.tgnet.tl.TL_keyboard.KeyboardButtonProto) button).getUrl());
                    }
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (String link : links) {
            if (link == null || !link.startsWith("http")) {
                continue;
            }
            android.net.Uri uri = android.net.Uri.parse(link);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : null;
            String path = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
            // Not products: Telegram links, images (the hidden photo link of AutoDeals posts,
            // Keepa price charts).
            boolean image = path.matches(".*\\.(png|jpe?g|gif|webp)$") || (host != null && host.endsWith("keepa.com"));
            if (host != null && !TELEGRAM_HOSTS.contains(host) && !image) {
                result.add(link);
            }
        }
        return result;
    }

    /**
     * "Pubblica sui miei canali" on a post: one link opens the sheet. Several are listed all ticked (up to the
     * multi post maximum): one left ticked opens the sheet, two or more a multi post.
     */
    public static void publishFromMessage(BaseFragment fragment, long dialogId, org.telegram.messenger.MessageObject message) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        List<String> links = messageLinks(message);
        if (links.isEmpty()) {
            org.telegram.ui.Components.BulletinFactory.of(fragment).createErrorBulletin(DealsGramText.get("NoProductLinks")).show();
        } else if (links.size() == 1) {
            new AutoDealsSheet(fragment, dialogId, links.get(0), null, null).from("message_menu").show();
        } else {
            Context context = fragment.getParentActivity();
            android.widget.LinearLayout column = new android.widget.LinearLayout(context);
            column.setOrientation(android.widget.LinearLayout.VERTICAL);
            List<org.telegram.ui.Cells.CheckBoxCell> cells = new ArrayList<>();
            for (int i = 0; i < links.size(); i++) {
                org.telegram.ui.Cells.CheckBoxCell cell = new org.telegram.ui.Cells.CheckBoxCell(context, 1, fragment.getResourceProvider());
                cell.setBackground(org.telegram.ui.ActionBar.Theme.getSelectorDrawable(false));
                cell.setText(linkLabel(links.get(i)), "", i < AutoDealsMultiSheet.MAX, false);
                cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked(), true));
                column.addView(cell, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, 50));
                cells.add(cell);
            }
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(column);
            new org.telegram.ui.ActionBar.AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(DealsGramText.get("WhichProducts"))
                .setView(scroll)
                .setPositiveButton(DealsGramText.get("Continue"), (dialog, which) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < cells.size(); i++) {
                        if (cells.get(i).isChecked()) {
                            picked.add(links.get(i));
                        }
                    }
                    if (picked.size() == 1) {
                        new AutoDealsSheet(fragment, dialogId, picked.get(0), null, null).from("message_menu").show();
                    } else if (picked.size() > 1) {
                        publishMultiFromMessage(fragment, picked);
                    }
                })
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .show();
        }
    }

    /** Links picked from a post: a multi post on one of the user's channels, asked when more than one can take it. */
    private static void publishMultiFromMessage(BaseFragment fragment, List<String> links) {
        int account = UserConfig.selectedAccount;
        org.telegram.ui.ActionBar.AlertDialog progress = new org.telegram.ui.ActionBar.AlertDialog(fragment.getParentActivity(), org.telegram.ui.ActionBar.AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            List<JSONObject> usable = new ArrayList<>();
            String error = null;
            boolean anyChannel = false;
            try {
                for (JSONObject channel : loadChannels(account)) {
                    anyChannel = true;
                    if (canMultiPost(channel)) {
                        usable.add(channel);
                    }
                }
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            }
            String errorText = error;
            boolean linked = anyChannel;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (errorText == null && usable.isEmpty()) {
                    // No linked channel: how to link one; linked but no plan with multi posts: what it unlocks.
                    if (linked) {
                        AutoDealsPlanSheet.showFeature(fragment.getParentActivity(), fragment.getResourceProvider(), AutoDealsPlanSheet.FEATURE_MULTI, 0);
                    } else {
                        AutoDealsPlanSheet.showConnect(fragment.getParentActivity(), fragment.getResourceProvider());
                    }
                } else if (errorText != null) {
                    org.telegram.ui.Components.BulletinFactory.of(fragment).createErrorBulletin(errorText).show();
                } else if (usable.size() == 1) {
                    openMulti(fragment, channelDialogId(usable.get(0)), usable.get(0), links, null, "message_menu");
                } else {
                    CharSequence[] titles = new CharSequence[usable.size()];
                    for (int i = 0; i < usable.size(); i++) {
                        titles[i] = channelTitle(usable.get(i));
                    }
                    new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                        .setTitle(DealsGramText.get("WhichChannel"))
                        .setItems(titles, (dialog, which) -> openMulti(fragment, channelDialogId(usable.get(which)), usable.get(which), links, null, "message_menu"))
                        .show();
                }
            });
        });
    }

    /** The Telegram dialog id of an AutoDeals channel (its id is the Bot API one, -100…). */
    private static long channelDialogId(JSONObject channel) {
        return channel.optLong("id") + 1000000000000L;
    }

    // --- Posts already published in the user's channels ----------------------

    private static final int POSTS_PAGE = 50;
    private static final int POSTS_MAX_PAGES = 20;

    /** "Offerta terminata" is part of the subscription: on a Free channel the menu item shows a lock. */
    public static boolean canExpire(long dialogId) {
        Map<Long, JSONObject> channels = cachedChannels(UserConfig.selectedAccount);
        return channels != null && hasSubscription(channels.get(botApiChannelId(dialogId)));
    }

    /** Whether the chat is one of the user's AutoDeals channels, from the cache (no network). */
    public static boolean isLinkedChannel(long dialogId) {
        Map<Long, JSONObject> channels = cachedChannels(UserConfig.selectedAccount);
        return channels != null && channels.containsKey(botApiChannelId(dialogId));
    }

    /**
     * "Offerta terminata" on a published post: AutoDeals marks the offer as ended and edits the message
     * in the channel, as the bot and the mini-app do. In a multi post the user picks which deals ended
     * (AutoDealsExpireSheet).
     */
    public static void expireFromMessage(BaseFragment fragment, long dialogId, org.telegram.messenger.MessageObject message) {
        if (fragment == null || fragment.getParentActivity() == null || message == null) {
            return;
        }
        long channelId = botApiChannelId(dialogId);
        if (!canExpire(dialogId)) {
            AutoDealsPlanSheet.showFeature(fragment.getParentActivity(), fragment.getResourceProvider(), AutoDealsPlanSheet.FEATURE_EXPIRE, channelId);
            return;
        }
        AutoDealsExpireSheet.start(fragment, channelId, message.getId());
    }

    /**
     * The AutoDeals post sent as this Telegram message, from the channel's published posts (newest first).
     * Message ids grow over time, so the search stops at the first page that reaches older messages.
     */
    static JSONObject findPublishedPost(AutoDealsApi api, long channelId, int messageId) throws Exception {
        for (int page = 0; page < POSTS_MAX_PAGES; page++) {
            JSONObject params = new JSONObject();
            params.put("channel_id", channelId);
            params.put("type", "posted");
            params.put("page", page);
            params.put("per_page", POSTS_PAGE);
            Object data = api.call("get_posts", params, MESSAGE);
            JSONArray posts = data instanceof JSONArray ? (JSONArray) data : new JSONArray();
            boolean older = false;
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = posts.optJSONObject(i);
                if (post == null || post.isNull("message_id")) {
                    continue;
                }
                long id = post.optLong("message_id");
                if (id == messageId) {
                    return post;
                }
                older |= id < messageId;
            }
            if (older || posts.length() < POSTS_PAGE) {
                return null;
            }
        }
        return null;
    }

    /** "amazon.it/dp/B01MG4A511" instead of the full link with tracking parameters. */
    private static String linkLabel(String link) {
        android.net.Uri uri = android.net.Uri.parse(link);
        String host = uri.getHost() != null ? uri.getHost().replaceFirst("^www\\.", "") : link;
        String path = uri.getPath() != null ? uri.getPath() : "";
        String label = host + path;
        return label.length() > 48 ? label.substring(0, 47) + "…" : label;
    }

    // --- Shared from other apps ----------------------------------------------

    public static final String ACTION_PUBLISH = "app.dealsgram.PUBLISH_LINK";
    public static final String EXTRA_LINK = "dealsgram_link";

    /** The first web link in shared text, e.g. "Guarda questo prodotto su Amazon … https://amzn.eu/d/…". */
    public static String findLink(CharSequence text) {
        if (text == null) {
            return null;
        }
        Matcher m = ANY_LINK.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * Opens the sheet for a link shared from another app, once the chat list is on screen.
     * Without an open channel it preselects the channels used last time.
     */
    public static void openShared(String link, int attempt) {
        if (TextUtils.isEmpty(link)) {
            return;
        }
        BaseFragment fragment = org.telegram.ui.LaunchActivity.getLastFragment();
        boolean ready = fragment != null && fragment.getParentActivity() != null
            && UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated();
        if (!ready) {
            if (attempt < 20) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> openShared(link, attempt + 1), 300);
            }
            return;
        }
        long dialogId = fragment instanceof org.telegram.ui.ChatActivity ? ((org.telegram.ui.ChatActivity) fragment).getDialogId() : 0;
        new AutoDealsSheet(fragment, dialogId, link, null, null).from("share").show();
    }

    // --- Last used channels ----------------------------------------------------

    static android.content.SharedPreferences prefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("dealsgram", Context.MODE_PRIVATE);
    }

    /** For the anonymous ping: whether this account already has channels linked to AutoDeals in cache. */
    static synchronized boolean hasLinkedChannels() {
        for (Map<Long, JSONObject> channels : channelsByAccount.values()) {
            if (channels != null && !channels.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static List<Long> lastChannels(int account) {
        List<Long> ids = new ArrayList<>();
        for (String part : prefs().getString("last_channels_" + account, "").split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        return ids;
    }

    static void saveLastChannels(int account, List<Long> ids) {
        prefs().edit().putString("last_channels_" + account, TextUtils.join(",", ids)).apply();
    }

    // --- Channel cache -------------------------------------------------------

    private static synchronized Map<Long, JSONObject> cachedChannels(int account) {
        Long at = channelsAt.get(account);
        if (at != null && SystemClock.elapsedRealtime() - at > CHANNELS_TTL_MS) {
            // Stale: still usable for this message, refreshed for the next one.
            refreshChannelsAsync(account);
        }
        return channelsByAccount.get(account);
    }

    private static void refreshChannelsAsync(int account) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                loadChannels(account);
            } catch (AutoDealsApi.AutoDealsException ignore) {
            }
        });
    }

    /** Blocking: the user's AutoDeals channels, also refreshing the cache. */
    static List<JSONObject> loadChannels(int account) throws AutoDealsApi.AutoDealsException {
        Object data = AutoDealsApi.getInstance(account).call("get_channels", new JSONObject());
        List<JSONObject> list = new ArrayList<>();
        Map<Long, JSONObject> byId = new HashMap<>();
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            for (int i = 0; i < array.length(); i++) {
                JSONObject channel = array.optJSONObject(i);
                if (channel != null && channel.optLong("id") != 0) {
                    list.add(channel);
                    byId.put(channel.optLong("id"), channel);
                }
            }
        }
        synchronized (AutoDealsPublisher.class) {
            channelsByAccount.put(account, byId);
            channelsAt.put(account, SystemClock.elapsedRealtime());
        }
        return list;
    }

    /**
     * Whether the channel can publish manual posts now: a subscription that allows them, or the Free
     * plan enabled on this channel with posts left today (as returned by the API).
     */
    static boolean canPublish(JSONObject channel) {
        JSONObject subscription = channel.optJSONObject("subscription");
        if (subscription != null && subscription.optBoolean("manual_posts")) {
            return true;
        }
        JSONObject free = channel.optJSONObject("free_plan");
        return free != null && free.optBoolean("manual_posts") && free.optInt("remaining") > 0;
    }

    /**
     * Subtitle and value of the AutoDeals row in Settings ("2 canali collegati", "Free"), from the cached
     * channels; when they are not loaded yet the row shows a generic subtitle and the cache is refreshed.
     */
    public static CharSequence[] settingsTexts(int account) {
        Map<Long, JSONObject> channels = cachedChannels(account);
        if (channels == null) {
            refreshChannelsAsync(account);
        }
        if (channels == null || channels.isEmpty()) {
            return new CharSequence[]{DealsGramText.get("SettingsNoChannels"), null};
        }
        boolean anySubscription = false;
        boolean anyFree = false;
        for (JSONObject channel : channels.values()) {
            anySubscription |= channel.optJSONObject("subscription") != null;
            anyFree |= freePlan(channel) != null;
        }
        String value = anySubscription ? DealsGramText.get("PlanActive") : anyFree ? DealsGramText.get("PlanFree") : DealsGramText.get("PlanNone");
        return new CharSequence[]{DealsGramText.plural("SettingsChannels", channels.size()), value};
    }

    /** Free-plan allowance for a channel without a subscription, or null. */
    static JSONObject freePlan(JSONObject channel) {
        return channel.optJSONObject("subscription") == null ? channel.optJSONObject("free_plan") : null;
    }

    /**
     * Editing the post, the note above it, the post in the message field and "Offerta terminata" need a
     * subscription on the channel: the Free plan publishes the post as its format makes it.
     */
    static boolean hasSubscription(JSONObject channel) {
        return channel != null && channel.optJSONObject("subscription") != null;
    }

    /** Whether any of the account's AutoDeals channels has a subscription, from the cache (no network). */
    static boolean anySubscription(int account) {
        Map<Long, JSONObject> channels = cachedChannels(account);
        if (channels != null) {
            for (JSONObject channel : channels.values()) {
                if (hasSubscription(channel)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether scheduling is available for the channel. */
    static boolean canSchedule(JSONObject channel) {
        return channel.optJSONObject("subscription") != null;
    }

    static boolean canMultiPost(JSONObject channel) {
        JSONObject subscription = channel.optJSONObject("subscription");
        return subscription != null && subscription.optBoolean("multi_posts", true);
    }

    /** Whether queue features are available for the channel. */
    static boolean hasAutopostingQueue(JSONObject channel) {
        JSONObject stats = channel.optJSONObject("stats");
        return stats != null && stats.optBoolean("autoposting_v2_active");
    }

    /** The channel's Telegram photo, or its initials when Telegram does not have the chat in memory. */
    static org.telegram.ui.Components.BackupImageView channelAvatar(Context context, int account, JSONObject channel, int radiusDp) {
        org.telegram.ui.Components.BackupImageView avatar = new org.telegram.ui.Components.BackupImageView(context);
        avatar.setRoundRadius(org.telegram.messenger.AndroidUtilities.dp(radiusDp));
        org.telegram.ui.Components.AvatarDrawable avatarDrawable = new org.telegram.ui.Components.AvatarDrawable();
        // AutoDeals ids are Bot API ids (-100xxxxxxxxxx); Telegram's chat id is the positive xxxxxxxxxx.
        long chatId = -(channel.optLong("id") + 1000000000000L);
        org.telegram.tgnet.TLRPC.Chat chat = org.telegram.messenger.MessagesController.getInstance(account).getChat(chatId);
        if (chat != null) {
            avatarDrawable.setInfo(account, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(chatId, channelTitle(channel), null);
            avatar.setImageDrawable(avatarDrawable);
        }
        avatar.setLayoutParams(org.telegram.ui.Components.LayoutHelper.createFrame(radiusDp * 2, radiusDp * 2));
        return avatar;
    }

    static String channelTitle(JSONObject channel) {
        String title = channel.optString("title", "");
        if (TextUtils.isEmpty(title)) {
            title = channel.optString("name", "");
        }
        return TextUtils.isEmpty(title) ? String.valueOf(channel.optLong("id")) : title;
    }

    static String clipboardLink(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence value = clip.getItemAt(0).coerceToText(context);
                Matcher m = value != null ? ANY_LINK.matcher(value) : null;
                if (m != null && m.find()) {
                    return m.group();
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    // --- Post prepared while the link is in the field ---------------------------

    /**
     * A link in the input field of a channel linked to AutoDeals is prepared before Send: the same scrape_url,
     * preview_post_message and image download that the sheet or the field post would start after Send.
     * Only on channels with a subscription: on the Free plan every scrape_url uses one of the day's publications,
     * so a link that is never sent would waste one.
     */
    static final class Prepared {
        final int account;
        final long channelId;
        final String link;
        final boolean editable;
        final long startedAt = SystemClock.elapsedRealtime();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        JSONObject product;
        JSONObject customData;
        JSONObject rendered;
        android.graphics.Bitmap bitmap;
        boolean failed;
        /** The channel list loaded at the same time, so the sheet does not ask for it again after Send. */
        volatile List<JSONObject> channels;

        Prepared(int account, long channelId, String link, boolean editable) {
            this.account = account;
            this.channelId = channelId;
            this.link = link;
            this.editable = editable;
        }

        boolean matches(int account, long channelId, String link) {
            return this.account == account && this.channelId == channelId && this.link.equals(link)
                && SystemClock.elapsedRealtime() - startedAt < PREPARED_TTL_MS;
        }

        void run() {
            try {
                AutoDealsApi.Origin origin = new AutoDealsApi.Origin(editable ? "field" : "sheet", "send_link");
                AutoDealsApi api = AutoDealsApi.getInstance(account);
                JSONObject p = scrape(api, channelId, link, origin);
                JSONObject c = customData(p);
                JSONObject params = new JSONObject();
                params.put("channel_id", channelId);
                params.put("offer_id", p.opt("offer_id"));
                params.put("custom_data", c);
                if (editable) {
                    params.put("editable", true);
                }
                Object data = api.call("preview_post_message", params, origin);
                JSONObject r = data instanceof JSONObject ? (JSONObject) data : new JSONObject();
                String type = r.optString("media_type");
                byte[] bytes = "media".equals(type) || "attached".equals(type)
                    ? AutoDealsApi.download(r.optString("product_image", null)) : null;
                bitmap = bytes != null ? android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length) : null;
                product = p;
                customData = c;
                rendered = r;
                log("ready in " + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            } catch (Exception e) {
                // After Send the screen asks again and shows its own error.
                failed = true;
                log("failed: " + e);
            } finally {
                done.countDown();
            }
        }
    }

    /** Pause after the last change of the field: a link still being typed or pasted is not prepared. */
    private static final long PREPARE_DELAY_MS = 500;
    /** Prices change: a post prepared longer ago than this is prepared again after Send. */
    private static final long PREPARED_TTL_MS = 3 * 60 * 1000L;
    private static final org.telegram.messenger.DispatchQueue prepareQueue = new org.telegram.messenger.DispatchQueue("autoDealsPrepare");
    private static Runnable pendingPrepare;
    private static Prepared prepared;

    /** Every change of the chat input field (UI thread). */
    public static void onFieldTextChanged(long dialogId, CharSequence text) {
        if (pendingPrepare != null) {
            org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(pendingPrepare);
            pendingPrepare = null;
        }
        if (dialogId >= 0 || text == null || text.length() > 2048 || !BARE_LINK.matcher(text).matches()) {
            return;
        }
        int account = UserConfig.selectedAccount;
        String link = text.toString().trim();
        pendingPrepare = () -> {
            pendingPrepare = null;
            prepare(account, dialogId, link);
        };
        org.telegram.messenger.AndroidUtilities.runOnUIThread(pendingPrepare, PREPARE_DELAY_MS);
    }

    private static void prepare(int account, long dialogId, String link) {
        Map<Long, JSONObject> channels = cachedChannels(account);
        JSONObject channel = channels != null ? channels.get(botApiChannelId(dialogId)) : null;
        JSONObject subscription = channel != null ? channel.optJSONObject("subscription") : null;
        if (subscription == null || !subscription.optBoolean("manual_posts")) {
            log(channel == null ? "not a linked channel" : "no subscription: not prepared");
            return; // Free plan, expired or not linked: prepared only after Send, as before.
        }
        long channelId = channel.optLong("id");
        Prepared job;
        synchronized (AutoDealsPublisher.class) {
            if (prepared != null && prepared.matches(account, channelId, link)) {
                return;
            }
            job = new Prepared(account, channelId, link, AutoDealsDraft.fieldMode());
            prepared = job;
        }
        log("preparing " + link + (job.editable ? " (field)" : " (sheet)"));
        prepareQueue.postRunnable(job::run);
        if (!job.editable) {
            // The sheet loads the channel list on this queue before the preview: it finds it ready.
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    job.channels = loadChannels(account);
                } catch (AutoDealsApi.AutoDealsException ignore) {
                }
            });
        }
    }

    /**
     * Background thread only: the post prepared for this link, waiting for it while it is still loading.
     * Used once; null when there is none, it is too old or it failed.
     */
    static Prepared takePrepared(int account, long channelId, String link, boolean editable) {
        Prepared job;
        synchronized (AutoDealsPublisher.class) {
            job = prepared;
            if (job == null || job.editable != editable || !job.matches(account, channelId, link)) {
                log("nothing prepared for " + link);
                return null;
            }
            prepared = null;
        }
        log(job.done.getCount() == 0 ? "used, already ready" : "used, waiting for it");
        try {
            if (!job.done.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            return null;
        }
        return job.failed ? null : job;
    }

    private static void log(String text) {
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            org.telegram.messenger.FileLog.d("DealsGram prepare: " + text);
        }
    }

    /** The channel list loaded together with the prepared post of this link, when it is still fresh. */
    static List<JSONObject> preparedChannels(int account, long dialogId, String link) {
        synchronized (AutoDealsPublisher.class) {
            Prepared job = prepared;
            return job != null && link != null && job.matches(account, botApiChannelId(dialogId), link.trim()) ? job.channels : null;
        }
    }

    // --- Request payloads ------------------------------------------------------

    static JSONObject scrape(AutoDealsApi api, long channelId, String link, AutoDealsApi.Origin origin) throws Exception {
        JSONObject params = new JSONObject();
        params.put("channel_id", channelId);
        params.put("url", link);
        Object data = api.call("scrape_url", params, origin);
        return data instanceof JSONObject ? (JSONObject) data : new JSONObject();
    }

    static JSONObject createParams(JSONObject channel, JSONObject product, JSONObject customData, String schedule, String note) throws Exception {
        return createParams(channel, product, customData, schedule, note, null);
    }

    /** noteHtml: the same note as Telegram HTML (top_note_format "html"), or null to send it as plain text. */
    static JSONObject createParams(JSONObject channel, JSONObject product, JSONObject customData, String schedule, String note, String noteHtml) throws Exception {
        JSONObject params = new JSONObject();
        params.put("channel_id", channel.optLong("id"));
        params.put("offer_id", product.opt("offer_id"));
        params.put("link", product.optString("link", ""));
        params.put("short_link", product.optString("short_link", ""));
        params.put("schedule", schedule);
        params.put("custom_data", customData);
        if (!TextUtils.isEmpty(noteHtml)) {
            params.put("top_note", noteHtml);
            params.put("top_note_format", "html");
        } else if (!TextUtils.isEmpty(note)) {
            params.put("top_note", note);
        }
        // Free plan: the attempt reserved by the extraction is returned at the top level.
        if (product.has("free_request_token") && !product.isNull("free_request_token")) {
            params.put("free_request_token", product.opt("free_request_token"));
        }
        return params;
    }

    /** Build the editable product metadata sent with a publication. */
    static JSONObject customData(JSONObject product) throws Exception {
        JSONObject data = new JSONObject();
        if (!product.optBoolean("is_universal")) {
            data.put("link", product.opt("link"));
            data.put("short_link", product.opt("short_link"));
            data.put("description", "");
            return data;
        }
        Object deal = product.opt("deal_price");
        Object original = product.opt("original_price");
        String dealType;
        if (product.has("coupon") && !product.isNull("coupon") && !TextUtils.isEmpty(product.optString("coupon"))) {
            dealType = "coupon";
        } else if (deal instanceof Number && original instanceof Number && ((Number) original).doubleValue() > ((Number) deal).doubleValue()) {
            dealType = "normal";
        } else {
            dealType = "nodiscount";
        }
        Object source = product.has("source_url") && !product.isNull("source_url") ? product.opt("source_url") : product.opt("link");
        JSONArray images = new JSONArray();
        if (product.has("image") && !product.isNull("image") && !TextUtils.isEmpty(product.optString("image"))) {
            images.put(product.opt("image"));
        }
        data.put("link", product.opt("link"));
        data.put("source_url", source);
        data.put("short_link", product.opt("short_link"));
        data.put("title", product.opt("title"));
        data.put("description", product.opt("description"));
        data.put("image", product.opt("image"));
        data.put("images", images);
        data.put("manufacturer", product.opt("manufacturer"));
        data.put("deal_type", dealType);
        data.put("deal_price", deal);
        data.put("original_price", original);
        data.put("coupon", product.opt("coupon"));
        data.put("store", product.opt("store"));
        data.put("nation", product.opt("nation"));
        data.put("store_name", product.opt("store_name"));
        data.put("store_domain", product.opt("store_domain"));
        data.put("availability_status", product.opt("availability_status"));
        data.put("is_universal", true);
        data.put("extraction_source", product.opt("extraction_source"));
        data.put("universal_extraction_status", product.opt("universal_extraction_status"));
        JSONArray missing = product.optJSONArray("missing_fields");
        data.put("missing_fields", missing != null ? missing : new JSONArray());
        data.put("affiliate_applied", product.optBoolean("affiliate_applied"));
        return data;
    }
}
