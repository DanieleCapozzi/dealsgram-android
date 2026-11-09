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
 * <li>{@link #showConnect}: the channel is not linked to AutoDeals yet;</li>
 * <li>{@link #showFirstSteps}: "Primi passi", once after the first login (and from Impostazioni DealsGram): the linked
 * channels, or how to link one, and the three things to try.</li>
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
    private static final int MODE_FIRST_STEPS = 4;

    /** "Primi passi" already shown on this account (DealsGram prefs). */
    private static final String FIRST_STEPS_SHOWN = "first_steps_shown_";
    /**
     * Debug builds only, for testing with an account that already has linked channels:
     * adb shell run-as app.dealsgram ... first_steps_test_empty=true shows the "no channel linked" version.
     */
    private static final String FIRST_STEPS_TEST_EMPTY = "first_steps_test_empty";
    private static final int NAVY = 0xff1d2133;

    /** "Primi passi" from Impostazioni DealsGram, any time. */
    public static void showFirstSteps(Context context, Theme.ResourcesProvider resourcesProvider) {
        new AutoDealsPlanSheet(context, resourcesProvider, MODE_FIRST_STEPS, null, null, 0).show();
    }

    /**
     * "Primi passi" once per account, from the chat list after login. Debug builds show it again after
     * clearing first_steps_shown_<account> in shared_prefs/dealsgram.xml (no reinstall, no new number).
     */
    public static void showFirstStepsIfDue(org.telegram.ui.ActionBar.BaseFragment fragment) {
        int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()
                || AutoDealsPublisher.prefs().getBoolean(FIRST_STEPS_SHOWN + account, false)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() == null || fragment.isFinished
                    || AutoDealsPublisher.prefs().getBoolean(FIRST_STEPS_SHOWN + account, false)) {
                return;
            }
            AutoDealsPublisher.prefs().edit().putBoolean(FIRST_STEPS_SHOWN + account, true).apply();
            showFirstSteps(fragment.getParentActivity(), fragment.getResourceProvider());
        }, 1200);
    }

    private static boolean testEmptyChannels() {
        return org.telegram.messenger.BuildVars.DEBUG_VERSION && AutoDealsPublisher.prefs().getBoolean(FIRST_STEPS_TEST_EMPTY, false);
    }

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
        this.isFirstSteps = mode == MODE_FIRST_STEPS;
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
        } else if (mode == MODE_FIRST_STEPS) {
            title = DealsGramText.get("FirstStepsTitle");
            text = DealsGramText.get("FirstStepsText");
            column.addView(appIcon(context));
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

        if (mode == MODE_FIRST_STEPS) {
            column.addView(label(context, DealsGramText.get("YourChannels")));
            loadChannels(card(context));
            column.addView(label(context, DealsGramText.get("FirstStepsTry")));
            column.addView(featureCarousel(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
            column.addView(label(context, DealsGramText.get("FollowTitle")));
            LinearLayout follow = card(context);
            follow.addView(followRow(context, "📢", "AutoDeals News", DealsGramText.get("FollowNewsText"), NEWS_USERNAME, true));
            follow.addView(followRow(context, "🤖", "@" + AutoDealsApi.BOT_USERNAME, DealsGramText.get("FollowBotText"), AutoDealsApi.BOT_USERNAME, false));
        } else if (mode == MODE_CONNECT) {
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
        main = new ButtonWithCounterView(context, resourcesProvider).setRound();
        if (mode == MODE_FIRST_STEPS) {
            // Until the channels are loaded; with none linked loadChannels() turns it into "Apri @AutoDealsProBot".
            main.setText(DealsGramText.get("FirstStepsDone"), false);
            main.setOnClickListener(v -> dismiss());
        } else if (mode == MODE_CONNECT) {
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

    private ButtonWithCounterView main;
    private final boolean isFirstSteps;

    private void loadChannels(LinearLayout card) {
        RadialProgressView progress = new RadialProgressView(getContext(), resourcesProvider);
        progress.setSize(dp(26));
        card.addView(progress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        Utilities.globalQueue.postRunnable(() -> {
            List<JSONObject> list = null;
            String error = null;
            try {
                list = testEmptyChannels() ? new java.util.ArrayList<>() : AutoDealsPublisher.loadChannels(account);
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
                    if (errorText == null && main != null && card.getParent() != null && isFirstSteps) {
                        // No channel yet: how to link one, and the main button opens the bot.
                        card.addView(step(getContext(), 1, DealsGramText.format("ConnectStep1", "@" + AutoDealsApi.BOT_USERNAME)));
                        card.addView(step(getContext(), 2, DealsGramText.get("ConnectStep2")));
                        card.addView(step(getContext(), 3, DealsGramText.get("ConnectStep3")));
                        // The same steps, recorded in the bot (the other channels are blurred).
                        card.addView(videoBox(new AutoDealsLinkModeSheet.LoopingVideo(getContext(), "dealsgram/aggiungi_canale.mp4")),
                            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 14));
                        main.setText(DealsGramText.format("OpenBot", "@" + AutoDealsApi.BOT_USERNAME), false);
                        main.setOnClickListener(v -> {
                            dismiss();
                            Browser.openUrl(getContext(), BOT_URL);
                        });
                    }
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

    // --- "Da provare": the feature videos of dealsgram.app, side by side ---------------------------------------

    /**
     * Scenes of the square feature videos, in this order, with their texts. The app streams the light copies of
     * dealsgram-website/public/video/app/ (720 px, 30 fps, about 1 MB), not the 1080 px ones of the site.
     */
    private static final String[] CAROUSEL_SCENES = {"incolla-e-invia", "pubblica-da-un-messaggio", "prossimi-post", "cerca-offerte", "post-multiplo", "offerta-terminata"};
    private static final String[] CAROUSEL_NAMES = {"FirstStepsPasteTitle", "PublishOnMyChannels", "UpcomingPosts", "TabSearch", "MultiPost", "DealExpired"};
    private static final String[] CAROUSEL_TEXTS = {"FirstStepsPasteText", "FirstStepsHoldText", "FirstStepsQueueText", "FirstStepsSearchText", "FirstStepsMultiText", "FirstStepsExpireText"};

    private final java.util.ArrayList<AutoDealsLinkModeSheet.LoopingVideo> carouselVideos = new java.util.ArrayList<>();

    /** Recorded in Italian, English, German and Spanish; the other languages show the English ones, as the site does. */
    private static String videoLanguageSuffix() {
        org.telegram.messenger.LocaleController.LocaleInfo info = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo();
        String code = info != null && info.shortName != null ? info.shortName.toLowerCase(Locale.ROOT) : "en";
        if (code.startsWith("it")) return "";
        if (code.startsWith("de")) return "-de";
        if (code.startsWith("es")) return "-es";
        return "-en";
    }

    /** Swipe between the features; only the card in view plays, streamed from dealsgram.app. */
    private View featureCarousel(Context context) {
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String suffix = videoLanguageSuffix();
        int cardWidth = Math.min(dp(280), (int) (AndroidUtilities.displaySize.x * 0.78f));
        for (int i = 0; i < CAROUSEL_SCENES.length; i++) {
            LinearLayout cardView = new LinearLayout(context);
            cardView.setOrientation(LinearLayout.VERTICAL);
            cardView.setPadding(dp(10), dp(10), dp(10), dp(12));
            cardView.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
            AutoDealsLinkModeSheet.LoopingVideo video = new AutoDealsLinkModeSheet.LoopingVideo(context,
                "https://dealsgram.app/video/app/" + CAROUSEL_SCENES[i] + suffix + ".mp4");
            video.setActive(i == 0);
            video.setLoadAllowed(i <= 1);
            carouselVideos.add(video);
            cardView.addView(videoBox(video), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextView name = text(context, DealsGramText.get(CAROUSEL_NAMES[i]), 15, Theme.key_windowBackgroundWhiteBlackText);
            name.setTypeface(AndroidUtilities.bold());
            cardView.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 10, 4, 0));
            // Three lines for every card, so they all have the same height.
            TextView description = text(context, DealsGramText.get(CAROUSEL_TEXTS[i]), 13, Theme.key_windowBackgroundWhiteGrayText);
            description.setLines(3);
            description.setEllipsize(TextUtils.TruncateAt.END);
            cardView.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 0));
            row.addView(cardView, new LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (i < CAROUSEL_SCENES.length - 1) {
                row.addView(new View(context), new LinearLayout.LayoutParams(dp(10), 1));
            }
        }
        scroller.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        int step = cardWidth + dp(10);
        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            int current = Math.round(x / (float) step);
            for (int i = 0; i < carouselVideos.size(); i++) {
                if (Math.abs(i - current) <= 1) {
                    carouselVideos.get(i).setLoadAllowed(true);
                }
                carouselVideos.get(i).setActive(i == current);
            }
        });
        // Snap to the nearest card when the finger lifts.
        scroller.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.postDelayed(() -> scroller.smoothScrollTo(Math.round(scroller.getScrollX() / (float) step) * step, 0), 80);
            }
            return false;
        });
        return scroller;
    }

    /** A square video with rounded corners, on the grey of the sheet until its first frame. */
    private View videoBox(AutoDealsLinkModeSheet.LoopingVideo video) {
        FrameLayout box = new FrameLayout(getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
            }
        };
        box.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        box.setClipToOutline(true);
        box.addView(video, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return box;
    }

    @Override
    public void dismissInternal() {
        for (AutoDealsLinkModeSheet.LoopingVideo video : carouselVideos) {
            video.release();
        }
        super.dismissInternal();
    }

    // --- "Resta aggiornato": the news channel and the bot, joined only when the user taps -----------------

    /** The AutoDeals news channel (t.me/AutoDealsNews). Telegram clients must not join channels on their own. */
    private static final String NEWS_USERNAME = "AutoDealsNews";

    /**
     * A row with a Join / Start button. The channel is joined in place (MessagesController.addUserToChat, as the
     * channel's own Join button); the bot opens in its chat, where Telegram asks to start it. Rows already done
     * (member of the channel, a chat with the bot) show their check mark.
     */
    private View followRow(Context context, String emoji, String title, String description, String username, boolean channel) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        TextView glyph = new TextView(context);
        glyph.setText(emoji);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        glyph.setGravity(Gravity.CENTER);
        row.addView(glyph, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, title, 15, Theme.key_windowBackgroundWhiteBlackText);
        name.setTypeface(AndroidUtilities.bold());
        texts.addView(name);
        texts.addView(text(context, description, 13, Theme.key_windowBackgroundWhiteGrayText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView button = text(context, DealsGramText.get(channel ? "FollowJoin" : "FollowStart"), 14, Theme.key_featuredStickers_buttonText);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(Theme.createRoundRectDrawable(dp(16), getThemedColor(Theme.key_featuredStickers_addButton)));
        ScaleStateListAnimator.apply(button, .06f, 1.2f);
        row.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        MessagesController messages = MessagesController.getInstance(account);
        Runnable markDone = () -> {
            button.setText("✅ " + DealsGramText.get(channel ? "FollowJoined" : "FollowStarted"));
            button.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            button.setBackground(Theme.createRoundRectDrawable(dp(16), getThemedColor(Theme.key_windowBackgroundGray)));
            button.setOnClickListener(null);
            button.setClickable(false);
        };
        long[] peer = {0};
        messages.getUserNameResolver().resolve(username, peerId -> {
            if (peerId == null || peerId == 0) {
                return;
            }
            peer[0] = peerId;
            if (channel) {
                TLRPC.Chat chat = messages.getChat(-peerId);
                if (chat != null && !chat.left && !chat.kicked) {
                    markDone.run();
                }
            } else if (messages.dialogs_dict.get(peerId) != null) {
                markDone.run();
            }
        });
        button.setOnClickListener(v -> {
            if (channel) {
                TLRPC.Chat chat = peer[0] != 0 ? messages.getChat(-peer[0]) : null;
                if (chat == null) {
                    Browser.openUrl(getContext(), "https://t.me/" + username);
                    return;
                }
                markDone.run();
                messages.addUserToChat(chat.id, UserConfig.getInstance(account).getCurrentUser(), 0, null, null, null);
            } else {
                dismiss();
                Browser.openUrl(getContext(), "https://t.me/" + username);
            }
        });
        return row;
    }

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
