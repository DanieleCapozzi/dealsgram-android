package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What AutoDeals adds and how to get it, for whoever does not have it yet:
 * <ul>
 * <li>{@link #showPlans}: the AutoDeals page of Settings, with the user's channels and their plan;</li>
 * <li>{@link #showFeature}: a feature the channel's plan does not include (the "locks");</li>
 * <li>{@link #showLimit}: the Free plan's posts of the day are over;</li>
 * <li>{@link #showConnect}: the channel is not linked to AutoDeals yet.</li>
 * </ul>
 * "Scopri i piani" opens the dashboard's plans panel on the channel ({@link #openCheckout}).
 */
public class AutoDealsPlanSheet extends BottomSheet {

    public static final String FEATURE_SCHEDULING = "scheduling";
    public static final String FEATURE_QUEUE = "queue";
    public static final String FEATURE_MULTI = "multi_posts";
    /** The AutoDeals post in the message field, editable, with a note (AutoDealsDraft). */
    public static final String FEATURE_FIELD = "field";
    public static final String FEATURE_EXPIRE = "expire";

    public static final String BOT_URL = "https://t.me/" + AutoDealsApi.BOT_USERNAME;
    public static final String DASHBOARD_URL = "https://dashboard.autodealsbot.it/";

    private static final int MODE_PLANS = 0;
    private static final int MODE_FEATURE = 1;
    private static final int MODE_LIMIT = 2;
    private static final int MODE_CONNECT = 3;
    private static final int NAVY = 0xff1d2133;

    public static void showPlans(Context context, Theme.ResourcesProvider resourcesProvider) {
        new AutoDealsPlanSheet(context, resourcesProvider, MODE_PLANS, null, null, 0).show();
    }

    /** channelId: the AutoDeals channel the locked feature was tapped on (0 when unknown). */
    public static void showFeature(Context context, Theme.ResourcesProvider resourcesProvider, String feature, long channelId) {
        new AutoDealsPlanSheet(context, resourcesProvider, MODE_FEATURE, feature, null, channelId).show();
    }

    /** Show the daily allowance supplied for a Free-plan channel. */
    public static void showLimit(Context context, Theme.ResourcesProvider resourcesProvider, JSONObject freePlan, long channelId) {
        new AutoDealsPlanSheet(context, resourcesProvider, MODE_LIMIT, null, freePlan, channelId).show();
    }

    public static void showConnect(Context context, Theme.ResourcesProvider resourcesProvider) {
        new AutoDealsPlanSheet(context, resourcesProvider, MODE_CONNECT, null, null, 0).show();
    }

    private ScrollView scroll;
    private final int account;
    private final LinearLayout column;
    /** The channel to subscribe, for the checkout link; 0 opens the plans of the user's first channel. */
    private final long channelId;

    @Override
    protected boolean canDismissWithSwipe() {
        return scroll == null || !scroll.canScrollVertically(-1);
    }

    private AutoDealsPlanSheet(Context context, Theme.ResourcesProvider resourcesProvider, int mode, String feature, JSONObject freePlan, long channelId) {
        super(context, true, resourcesProvider);
        this.account = UserConfig.selectedAccount;
        this.channelId = channelId;
        int gray = getThemedColor(Theme.key_windowBackgroundGray);
        setBackgroundColor(gray);
        fixNavigationBar(gray);
        setApplyBottomPadding(false);

        FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = AndroidUtilities.displaySize.y - AndroidUtilities.statusBarHeight - dp(56);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(heightMeasureSpec), max), MeasureSpec.AT_MOST));
            }
        };
        scroll = new ScrollView(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(mode == MODE_LIMIT ? 6 + 48 + 44 + 12 : 6 + 48 + 12 + 8));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout header = new FrameLayout(context);
        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), .10f)));
        ScaleStateListAnimator.apply(close, .1f, 1.5f);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        String title;
        String text;
        if (mode == MODE_LIMIT) {
            int limit = freePlan != null ? freePlan.optInt("daily_limit", 4) : 4;
            title = DealsGramText.format("LimitTitle", limit);
            text = DealsGramText.get("LimitText");
            column.addView(badge(context, null, "⏳", 0xfffff1df));
        } else if (mode == MODE_FEATURE) {
            title = DealsGramText.get(featureKey(feature) + "Title");
            text = DealsGramText.get(featureKey(feature) + "Text");
            column.addView(badge(context, featureIcon(feature), null, Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .12f)));
        } else if (mode == MODE_CONNECT) {
            title = DealsGramText.get("ConnectTitle");
            text = DealsGramText.get("ConnectText");
            column.addView(appIcon(context));
        } else {
            title = DealsGramText.get("PlansTitle");
            text = DealsGramText.get("PlansText");
            column.addView(appIcon(context));
        }
        column.addView(heroText(context, title, 20, true, Theme.key_windowBackgroundWhiteBlackText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 0));
        column.addView(heroText(context, text, 14, false, Theme.key_windowBackgroundWhiteGrayText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 16));

        if (mode == MODE_CONNECT) {
            LinearLayout steps = card(context);
            steps.addView(step(context, 1, DealsGramText.format("ConnectStep1", "@" + AutoDealsApi.BOT_USERNAME)));
            steps.addView(step(context, 2, DealsGramText.get("ConnectStep2")));
            steps.addView(step(context, 3, DealsGramText.get("ConnectStep3")));
        } else {
            if (mode == MODE_PLANS) {
                column.addView(label(context, DealsGramText.get("YourChannels")));
                loadChannels(card(context));
                column.addView(label(context, DealsGramText.get("WithSubscription")));
            }
            LinearLayout features = card(context);
            features.addView(featureRow(context, R.drawable.msg_send, DealsGramText.get("PerkUnlimitedTitle"), DealsGramText.get("PerkUnlimitedText")));
            features.addView(featureRow(context, R.drawable.msg_bot, DealsGramText.get("PerkAutoTitle"), DealsGramText.get("PerkAutoText")));
            features.addView(featureRow(context, R.drawable.msg_calendar2, DealsGramText.get("PerkScheduleTitle"), DealsGramText.get("PerkScheduleText")));
            features.addView(featureRow(context, R.drawable.msg_list, DealsGramText.get("PerkMultiTitle"), DealsGramText.get("PerkMultiText")));
            features.addView(featureRow(context, R.drawable.msg_photos, DealsGramText.get("PerkFormatsTitle"), DealsGramText.get("PerkFormatsText")));
            features.addView(featureRow(context, R.drawable.msg_stats, DealsGramText.get("PerkLowestTitle"), DealsGramText.get("PerkLowestText")));
            features.addView(featureRow(context, R.drawable.menu_shop, DealsGramText.get("PerkStoresTitle"), DealsGramText.get("PerkStoresText")));
            features.addView(featureRow(context, R.drawable.msg_link, DealsGramText.get("PerkShortlinkTitle"), DealsGramText.get("PerkShortlinkText")));
            features.addView(featureRow(context, R.drawable.msg_share, DealsGramText.get("PerkSocialTitle"), DealsGramText.get("PerkSocialText")));
        }

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(dp(12), dp(6), dp(12), dp(12));
        buttons.setBackgroundColor(gray);
        ButtonWithCounterView main = new ButtonWithCounterView(context, resourcesProvider).setRound();
        if (mode == MODE_CONNECT) {
            main.setText(DealsGramText.format("OpenBot", "@" + AutoDealsApi.BOT_USERNAME), false);
            main.setOnClickListener(v -> {
                dismiss();
                Browser.openUrl(getContext(), BOT_URL);
            });
        } else {
            main.setText(DealsGramText.get("SeePlans"), false);
            main.setOnClickListener(v -> {
                dismiss();
                openCheckout(getContext(), channelId);
            });
        }
        buttons.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        if (mode == MODE_LIMIT) {
            ButtonWithCounterView later = new ButtonWithCounterView(context, false, resourcesProvider).setRound();
            later.setText(DealsGramText.get("BackTomorrow"), false);
            later.setOnClickListener(v -> dismiss());
            buttons.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 4, 0, 0));
        }
        root.addView(buttons, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        setCustomView(root);
    }

    /**
     * The dashboard's plans panel ("Attiva un piano", Stripe through SubManager) in the phone's browser, with
     * the channel preselected (?checkout_channel=, autodeals-desktop-svelte src/routes/+page.server.ts).
     * channelId 0 opens the dashboard home.
     */
    public static void openCheckout(Context context, long channelId) {
        String url = channelId != 0 ? DASHBOARD_URL + "?checkout_channel=" + channelId : DASHBOARD_URL;
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Browser.openUrl(context, url);
        }
    }

    private static String featureKey(String feature) {
        if (FEATURE_QUEUE.equals(feature)) {
            return "FeatureQueue";
        }
        if (FEATURE_MULTI.equals(feature)) {
            return "FeatureMulti";
        }
        if (FEATURE_FIELD.equals(feature)) {
            return "FeatureField";
        }
        if (FEATURE_EXPIRE.equals(feature)) {
            return "FeatureExpire";
        }
        return "FeatureSchedule";
    }

    private static int featureIcon(String feature) {
        if (FEATURE_QUEUE.equals(feature)) {
            return R.drawable.msg_list;
        }
        if (FEATURE_MULTI.equals(feature)) {
            return R.drawable.msg_check_s;
        }
        if (FEATURE_FIELD.equals(feature)) {
            return R.drawable.msg_edit;
        }
        if (FEATURE_EXPIRE.equals(feature)) {
            return R.drawable.msg_block;
        }
        return R.drawable.msg_calendar2;
    }

    // --- Channels --------------------------------------------------------------------

    private void loadChannels(LinearLayout card) {
        RadialProgressView progress = new RadialProgressView(getContext(), resourcesProvider);
        progress.setSize(dp(26));
        card.addView(progress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        Utilities.globalQueue.postRunnable(() -> {
            List<JSONObject> list = null;
            String error = null;
            try {
                list = AutoDealsPublisher.loadChannels(account);
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            }
            List<JSONObject> result = list;
            String errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                card.removeAllViews();
                if (result == null || result.isEmpty()) {
                    TextView none = text(getContext(), errorText != null ? errorText : DealsGramText.get("NoLinkedChannels"), 14, Theme.key_windowBackgroundWhiteGrayText);
                    none.setPadding(dp(16), dp(14), dp(16), dp(14));
                    card.addView(none);
                    return;
                }
                for (JSONObject channel : result) {
                    card.addView(channelRow(channel));
                }
            });
        });
    }

    private View channelRow(JSONObject channel) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(18));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        long chatId = -(channel.optLong("id") + 1000000000000L);
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(chatId);
        String title = AutoDealsPublisher.channelTitle(channel);
        if (chat != null) {
            avatarDrawable.setInfo(account, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(chatId, title, null);
            avatar.setImageDrawable(avatarDrawable);
        }
        row.addView(avatar, LayoutHelper.createLinear(36, 36, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, title, 15, Theme.key_windowBackgroundWhiteBlackText);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(text(context, planLine(channel), 13, Theme.key_windowBackgroundWhiteGrayText));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        JSONObject subscription = channel.optJSONObject("subscription");
        JSONObject free = channel.optJSONObject("free_plan");
        TextView pill = new TextView(context);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        pill.setTypeface(AndroidUtilities.bold());
        pill.setPadding(dp(8), dp(3), dp(8), dp(3));
        int color;
        if (subscription != null) {
            pill.setText(DealsGramText.get("PlanActive"));
            color = 0xff36a852;
        } else if (free != null) {
            pill.setText(DealsGramText.get("PlanFree"));
            color = 0xfff08c00;
        } else {
            pill.setText(DealsGramText.get("PlanNone"));
            color = getThemedColor(Theme.key_text_RedRegular);
        }
        pill.setTextColor(color);
        pill.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(color, .12f)));
        row.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 0));
        return row;
    }

    /** "Piano Pro · fino al 12 nov", "Free · ne restano 2 su 4 oggi", "Abbonamento scaduto". */
    static String planLine(JSONObject channel) {
        JSONObject subscription = channel.optJSONObject("subscription");
        if (subscription != null) {
            String end = formatDay(channel.optString("subscription_end", ""));
            String plan = subscription.optString("name", "");
            return end != null ? DealsGramText.format("PlanUntil", plan, end) : DealsGramText.format("PlanName", plan);
        }
        JSONObject free = channel.optJSONObject("free_plan");
        if (free != null) {
            if (!free.optBoolean("manual_posts", true)) {
                return DealsGramText.get("FreeChannelLimit");
            }
            return DealsGramText.format("FreeLeft", free.optInt("remaining"), free.optInt("daily_limit"));
        }
        return DealsGramText.get("PlanExpired");
    }

    private static String formatDay(String serverDate) {
        if (TextUtils.isEmpty(serverDate) || "null".equals(serverDate)) {
            return null;
        }
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(serverDate.substring(0, Math.min(10, serverDate.length())));
            return date != null ? new SimpleDateFormat(DealsGramText.get("DateDayMonth"), DealsGramText.locale()).format(date) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // --- Pieces ----------------------------------------------------------------------

    private View appIcon(Context context) {
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageResource(R.mipmap.icon_foreground_sa);
        icon.setBackground(Theme.createCircleDrawable(dp(72), NAVY));
        FrameLayout box = new FrameLayout(context);
        box.addView(icon, LayoutHelper.createFrame(72, 72, Gravity.CENTER));
        box.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72));
        return box;
    }

    private View badge(Context context, Integer iconRes, String emoji, int background) {
        FrameLayout box = new FrameLayout(context);
        FrameLayout circle = new FrameLayout(context);
        circle.setBackground(Theme.createCircleDrawable(dp(68), background));
        if (iconRes != null) {
            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(iconRes);
            icon.setColorFilter(getThemedColor(Theme.key_featuredStickers_addButton));
            icon.setScaleX(1.4f);
            icon.setScaleY(1.4f);
            circle.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } else {
            TextView glyph = new TextView(context);
            glyph.setText(emoji);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
            glyph.setGravity(Gravity.CENTER);
            circle.addView(glyph, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        box.addView(circle, LayoutHelper.createFrame(68, 68, Gravity.CENTER));
        box.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 68));
        return box;
    }

    private TextView heroText(Context context, String value, int sizeDp, boolean bold, int colorKey) {
        TextView view = text(context, value, sizeDp, colorKey);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        } else {
            view.setLineSpacing(dp(2), 1f);
        }
        return view;
    }

    private TextView text(Context context, String value, int sizeDp, int colorKey) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTextColor(getThemedColor(colorKey));
        return view;
    }

    private TextView label(Context context, String value) {
        TextView view = text(context, value, 14, Theme.key_windowBackgroundWhiteBlueHeader);
        view.setTypeface(AndroidUtilities.bold());
        view.setPadding(dp(28), dp(6), dp(28), dp(8));
        return view;
    }

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(4), 0, dp(4));
        card.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        column.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 12));
        return card;
    }

    private View featureRow(Context context, int iconRes, String title, String description) {
        LinearLayout row = new LinearLayout(context);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getThemedColor(Theme.key_featuredStickers_addButton));
        row.addView(icon, LayoutHelper.createLinear(28, 28, Gravity.TOP, 0, 0, 14, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, title, 15, Theme.key_windowBackgroundWhiteBlackText);
        name.setTypeface(AndroidUtilities.bold());
        texts.addView(name);
        texts.addView(text(context, description, 13, Theme.key_windowBackgroundWhiteGrayText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    private View step(Context context, int number, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        TextView num = text(context, String.valueOf(number), 13, Theme.key_featuredStickers_addButton);
        num.setTypeface(AndroidUtilities.bold());
        num.setGravity(Gravity.CENTER);
        num.setBackground(Theme.createCircleDrawable(dp(24), Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .12f)));
        row.addView(num, LayoutHelper.createLinear(24, 24, Gravity.TOP, 0, 0, 12, 0));
        TextView line = text(context, value, 14, Theme.key_windowBackgroundWhiteBlackText);
        line.setText(AndroidUtilities.replaceTags(value));
        row.addView(line, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }
}
