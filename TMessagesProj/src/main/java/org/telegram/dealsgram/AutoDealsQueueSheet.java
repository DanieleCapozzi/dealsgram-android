package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AIEditorAlert;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Channel queue, scheduled posts and offer search, with estimated publication times. */
public class AutoDealsQueueSheet extends BottomSheet {

    /** DealsGram: a swipe down closes the sheet only from the top of the list; elsewhere it scrolls the list. */
    @Override
    protected boolean canDismissWithSwipe() {
        return scroll == null || !scroll.canScrollVertically(-1);
    }

    private static final AutoDealsApi.Origin ORIGIN = new AutoDealsApi.Origin("queue", "chat_menu");
    private static final int TAB_QUEUE = 0;
    private static final int TAB_SCHEDULED = 1;
    private static final int TAB_SEARCH = 2;
    private static final int QUEUE_SIZE = 20;
    /** Same page size as the dashboard search. */
    private static final int SEARCH_PAGE = 24;
    /** The server searches by keyword from 3 characters; shorter, it browses the recent offers. */
    private static final int MIN_QUERY = 3;
    /** Timezone of the AutoDeals server, used for posts.scheduled_at. */
    private static final String SERVER_TIMEZONE = "Europe/Rome";

    private final BaseFragment fragment;
    private final int account;
    /** The channel shown; the pill in the header switches to another of the user's channels. */
    private long dialogId;
    private long channelId;
    private final List<JSONObject> userChannels = new ArrayList<>();
    private LinearLayout channelPill;
    private FrameLayout pillAvatar;
    private TextView pillText;
    private ImageView settingsButton;

    private final AIEditorAlert.Tabs tabs;
    private AIEditorAlert.Tabs.Tab queueTab;
    private AIEditorAlert.Tabs.Tab scheduledTab;
    private final TextView note;
    private final LinearLayout list;
    private final FrameLayout card;
    private final RadialProgressView progress;
    private final FrameLayout bulletinContainer;
    private final ScrollView scroll;
    private final LinearLayout searchBar;
    private final EditText searchField;
    private final TextView sortChip;
    private final TextView filtersChip;

    private int tab = TAB_QUEUE;
    private LinearLayout queueFilter;
    private TextView allChip;
    private TextView mineChip;
    /** Queue tab showing only the curated queue. */
    private boolean onlyMine;
    private int loadToken;
    private boolean v2;
    private JSONObject config;
    private JSONObject nextHour;
    private final List<JSONObject> curated = new ArrayList<>();
    private final List<JSONObject> live = new ArrayList<>();
    private final List<JSONObject> scheduled = new ArrayList<>();
    private String loadError;
    private JSONObject channel;

    private AutoDealsSearchFilters filters = new AutoDealsSearchFilters();
    private final List<JSONObject> results = new ArrayList<>();
    /** category_facets of the first page, for the filters. */
    private final List<JSONObject> facets = new ArrayList<>();
    /** Active sources of the channel ("STORE|NATION"), for the store filter. */
    private final List<String> sourceKeys = new ArrayList<>();
    private String searchedQuery = "";
    private int searchPage;
    private int searchToken;
    private boolean searchHasMore;
    private boolean searchLoading;
    private boolean searchLoaded;
    private String searchError;
    private final Runnable searchDebounce = () -> search(false);

    /**
     * Offers picked for a multi post. They stay picked across keywords, filters and sorts, as on the
     * dashboard, until the post is created or the selection is cancelled.
     */
    private final List<JSONObject> picks = new ArrayList<>();
    /** Check marks and rows of the search results on screen, by offer id. */
    private final java.util.Map<Long, View> pickMarks = new java.util.HashMap<>();
    private final java.util.Map<Long, View> pickRows = new java.util.HashMap<>();
    private ButtonWithCounterView addButton;
    private ButtonWithCounterView clearButton;
    private ButtonWithCounterView multiButton;

    public static void open(BaseFragment fragment, long dialogId) {
        if (fragment != null && fragment.getParentActivity() != null) {
            new AutoDealsQueueSheet(fragment, dialogId).show();
        }
    }

    private AutoDealsQueueSheet(BaseFragment fragment, long dialogId) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.account = UserConfig.selectedAccount;
        this.dialogId = dialogId;
        this.channelId = AutoDealsPublisher.botApiChannelId(dialogId);

        Context context = getContext();
        int gray = getThemedColor(Theme.key_windowBackgroundGray);
        setBackgroundColor(gray);
        fixNavigationBar(gray);
        setApplyBottomPadding(false);

        // A fixed height, so switching tabs does not resize the sheet.
        FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = (int) ((AndroidUtilities.displaySize.y - AndroidUtilities.statusBarHeight) * 0.86f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 6 + 48 + 12));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(DealsGramText.get("UpcomingPosts"));
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, 14, 20, 1, TypedValue.COMPLEX_UNIT_DIP);
        header.addView(title, LayoutHelper.createLinear(0, 28, 1f, 22, 0, 8, 0));
        // Channel pill, as in Pubblica offerta: the open channel first, a tap switches to another one.
        channelPill = new LinearLayout(context);
        channelPill.setGravity(Gravity.CENTER_VERTICAL);
        channelPill.setPadding(dp(4), dp(4), dp(10), dp(4));
        channelPill.setBackground(Theme.createRoundRectDrawable(dp(16), getThemedColor(Theme.key_windowBackgroundWhite)));
        pillAvatar = new FrameLayout(context);
        channelPill.addView(pillAvatar, LayoutHelper.createLinear(24, 24, 0, 0, 6, 0));
        pillText = new TextView(context);
        pillText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        pillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pillText.setSingleLine(true);
        pillText.setEllipsize(TextUtils.TruncateAt.END);
        pillText.setMaxWidth(dp(130));
        channelPill.addView(pillText);
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.arrow_more);
        arrow.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon));
        channelPill.addView(arrow, LayoutHelper.createLinear(16, 16, 4, 0, 0, 0));
        ScaleStateListAnimator.apply(channelPill, .05f, 1.2f);
        channelPill.setOnClickListener(v -> pickChannel());
        channelPill.setVisibility(View.GONE);
        header.addView(channelPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        settingsButton = new ImageView(context);
        settingsButton.setScaleType(ImageView.ScaleType.CENTER);
        settingsButton.setImageResource(R.drawable.msg_settings);
        settingsButton.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        settingsButton.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), .10f)));
        settingsButton.setContentDescription(DealsGramText.get("AutopostingSettings"));
        ScaleStateListAnimator.apply(settingsButton, .1f, 1.5f);
        settingsButton.setOnClickListener(v -> openSettings(""));
        settingsButton.setVisibility(View.GONE);
        header.addView(settingsButton, LayoutHelper.createLinear(44, 44, 4, 0, 0, 0));
        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), .10f)));
        ScaleStateListAnimator.apply(close, .1f, 1.5f);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createLinear(48, 48, 4, 0, 8, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));

        tabs = new AIEditorAlert.Tabs(context, account, false, resourcesProvider);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite)));
        tabs.setRoundRadius(28);
        queueTab = tabs.addTab(R.drawable.msg_list, DealsGramText.get("TabQueue"), this::selectTab);
        scheduledTab = tabs.addTab(R.drawable.msg_calendar2, DealsGramText.get("TabScheduled"), this::selectTab);
        tabs.addTab(R.drawable.msg_search, DealsGramText.get("TabSearch"), this::selectTab);
        tabs.selectTab(TAB_QUEUE, false);
        column.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 12, 0, 12, 8));

        note = new TextView(context);
        note.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setLineSpacing(dp(1), 1f);
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        // Queue: all of it in publishing order, or only the products the user queued (the dashboard's "Curata").
        queueFilter = new LinearLayout(context);
        allChip = barChip();
        allChip.setOnClickListener(v -> setOnlyMine(false));
        queueFilter.addView(allChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
        mineChip = barChip();
        mineChip.setOnClickListener(v -> setOnlyMine(true));
        queueFilter.addView(mineChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        queueFilter.setVisibility(View.GONE);
        column.addView(queueFilter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, 12, 0, 12, 8));

        // Search: keyword, sort and filters, as in the dashboard.
        searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.VERTICAL);
        searchBar.setVisibility(View.GONE);
        LinearLayout field = new LinearLayout(context);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(Theme.createRoundRectDrawable(dp(22), getThemedColor(Theme.key_windowBackgroundWhite)));
        ImageView glass = new ImageView(context);
        glass.setImageResource(R.drawable.msg_search);
        glass.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon));
        glass.setScaleType(ImageView.ScaleType.CENTER);
        field.addView(glass, LayoutHelper.createLinear(44, 44));
        searchField = new EditText(context);
        searchField.setHint(DealsGramText.get("SearchHint"));
        searchField.setSingleLine(true);
        searchField.setBackground(null);
        searchField.setPadding(0, 0, dp(16), 0);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchField.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            AndroidUtilities.cancelRunOnUIThread(searchDebounce);
            AndroidUtilities.hideKeyboard(searchField);
            search(false);
            return true;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // A keyword under 3 characters does not change the results, so it does not reload them.
                AndroidUtilities.cancelRunOnUIThread(searchDebounce);
                if (!currentQuery().equals(searchedQuery)) {
                    AndroidUtilities.runOnUIThread(searchDebounce, 450);
                }
            }
        });
        field.addView(searchField, LayoutHelper.createLinear(0, 44, 1f));
        searchBar.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 0, 12, 8));
        LinearLayout chips = new LinearLayout(context);
        sortChip = barChip();
        sortChip.setOnClickListener(v -> pickSort());
        chips.addView(sortChip, LayoutHelper.createLinear(0, 36, 1f, 0, 0, 8, 0));
        filtersChip = barChip();
        filtersChip.setOnClickListener(v -> openFilters());
        chips.addView(filtersChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        searchBar.addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 12, 0, 12, 8));
        column.addView(searchBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateSearchChips();

        card = new FrameLayout(context);
        card.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        scroll = new ScrollView(context);
        // Next search page when the list is scrolled near its end.
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (tab == TAB_SEARCH && searchHasMore && !searchLoading && searchError == null
                && y + v.getHeight() >= ((ScrollView) v).getChildAt(0).getHeight() - dp(400)) {
                search(true);
            }
        });
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(6));
        scroll.addView(list);
        card.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        progress = new RadialProgressView(context, resourcesProvider);
        progress.setSize(dp(32));
        card.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
        column.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 12, 0, 12, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setPadding(dp(12), dp(6), dp(12), dp(12));
        buttons.setBackgroundColor(gray);
        addButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        addButton.setText(DealsGramText.get("AddDeal"), false);
        addButton.setOnClickListener(v -> {
            dismiss();
            AutoDealsPublisher.start(fragment, dialogId);
        });
        buttons.addView(addButton, LayoutHelper.createLinear(0, 48, 1f, Gravity.FILL));
        clearButton = new ButtonWithCounterView(context, false, resourcesProvider).setRound();
        clearButton.setText(DealsGramText.get("Cancel"), false);
        clearButton.setOnClickListener(v -> {
            picks.clear();
            refreshPicks();
        });
        buttons.addView(clearButton, LayoutHelper.createLinear(0, 48, 0.6f, Gravity.FILL, 0, 0, 10, 0));
        multiButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        multiButton.setOnClickListener(v -> openMulti());
        buttons.addView(multiButton, LayoutHelper.createLinear(0, 48, 1.4f, Gravity.FILL));
        root.addView(buttons, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        bulletinContainer = new FrameLayout(context);
        root.addView(bulletinContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.BOTTOM, 0, 0, 0, 66));

        setCustomView(root);
        refreshPicks();
        load(false);
    }

    private void selectTab(int index) {
        tab = index;
        tabs.selectTab(index, true);
        searchBar.setVisibility(index == TAB_SEARCH ? View.VISIBLE : View.GONE);
        if (index != TAB_SEARCH) {
            AndroidUtilities.hideKeyboard(searchField);
        }
        if (index == TAB_SEARCH && !searchLoaded && !searchLoading && loadError == null) {
            search(false);
        } else {
            render();
        }
    }

    // --- Loading -----------------------------------------------------------------

    /**
     * Channel, scheduled posts and, with V2 on, the curated and generated queues. Quiet, it refreshes
     * them behind the search results without touching the list.
     */
    private void load(boolean quiet) {
        int token = ++loadToken;
        if (!quiet) {
            showLoading();
        }
        Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            boolean[] notLinkedResult = {false};
            boolean hasV2 = false;
            JSONObject newConfig = null;
            JSONObject newNextHour = null;
            List<JSONObject> newCurated = new ArrayList<>();
            List<JSONObject> newLive = new ArrayList<>();
            List<JSONObject> newScheduled = new ArrayList<>();
            JSONObject foundChannel = null;
            List<JSONObject> allChannels = new ArrayList<>();
            try {
                AutoDealsApi api = AutoDealsApi.getInstance(account);
                JSONObject channel = null;
                allChannels.addAll(AutoDealsPublisher.loadChannels(account));
                for (JSONObject c : allChannels) {
                    if (c.optLong("id") == channelId) {
                        channel = c;
                    }
                }
                if (channel == null) {
                    throw new AutoDealsApi.AutoDealsException("channel_not_found", null);
                }
                foundChannel = channel;
                hasV2 = AutoDealsPublisher.hasAutopostingQueue(channel);

                JSONObject params = new JSONObject();
                params.put("channel_id", channelId);
                params.put("type", "scheduled");
                params.put("per_page", 50);
                // Posts whose time has passed without being published (rejected or stuck) still count as
                // scheduled on the server, but will not go out: only the coming ones are listed.
                long since = System.currentTimeMillis() - 10 * 60 * 1000L;
                for (JSONObject post : objects(api.call("get_posts", params, ORIGIN))) {
                    // The curated queue also comes as virtual rows here: it has its own list.
                    Date at = parse(post.optString("scheduled_at"), TimeZone.getTimeZone(SERVER_TIMEZONE));
                    if (!"autopost_v2_queue".equals(post.optString("type")) && at != null && at.getTime() >= since) {
                        newScheduled.add(post);
                    }
                }
                Collections.sort(newScheduled, (a, b) -> a.optString("scheduled_at").compareTo(b.optString("scheduled_at")));

                if (hasV2) {
                    params = new JSONObject();
                    params.put("channel_id", channelId);
                    params.put("limit", 50);
                    Object curatedData = api.call("get_autoposting_v2_curated_queue", params, ORIGIN);
                    if (curatedData instanceof JSONObject) {
                        newCurated.addAll(objects(((JSONObject) curatedData).optJSONArray("items")));
                    }
                    params = new JSONObject();
                    params.put("channel_id", channelId);
                    params.put("page", 0);
                    params.put("per_page", QUEUE_SIZE);
                    Object liveData = api.call("get_autoposting_v2_live_queue", params, ORIGIN);
                    if (liveData instanceof JSONObject) {
                        JSONObject data = (JSONObject) liveData;
                        newLive.addAll(objects(data.optJSONArray("items")));
                        newConfig = data.optJSONObject("config");
                        newNextHour = data.optJSONObject("next_hour");
                    }
                }
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
                notLinkedResult[0] = "channel_not_found".equals(e.code);
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
                error = DealsGramText.get("QueueLoadFailed");
            }
            String errorText = error;
            boolean v2On = hasV2;
            JSONObject configResult = newConfig;
            JSONObject nextResult = newNextHour;
            JSONObject channelResult = foundChannel;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != loadToken) {
                    return;
                }
                if (notLinkedResult[0]) {
                    // Not linked to AutoDeals: how to link it, instead of an empty panel.
                    dismiss();
                    AutoDealsPlanSheet.showConnect(getContext(), resourcesProvider);
                    return;
                }
                loadError = errorText;
                channel = channelResult;
                replace(userChannels, allChannels);
                updatePill();
                v2 = v2On;
                config = configResult;
                nextHour = nextResult;
                replace(curated, newCurated);
                replace(live, newLive);
                replace(scheduled, newScheduled);
                if (tab != TAB_SEARCH) {
                    render();
                } else if (!searchLoaded && !searchLoading) {
                    search(false);
                }
            });
        });
    }

    private void updatePill() {
        settingsButton.setVisibility(channel != null && channel.optJSONObject("subscription") != null ? View.VISIBLE : View.GONE);
        if (userChannels.size() < 2 || channel == null) {
            channelPill.setVisibility(View.GONE);
            return;
        }
        pillAvatar.removeAllViews();
        pillAvatar.addView(avatar(channel, 12), LayoutHelper.createFrame(24, 24));
        pillText.setText(AutoDealsPublisher.channelTitle(channel));
        channelPill.setVisibility(View.VISIBLE);
    }

    /** The user's channels with photo and plan; the chosen one replaces the one shown. */
    private void pickChannel() {
        Context context = getContext();
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] dialog = new AlertDialog[1];
        for (JSONObject item : userChannels) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), 0, dp(20), 0);
            row.setBackground(Theme.getSelectorDrawable(false));
            row.addView(avatar(item, 21), LayoutHelper.createLinear(42, 42, 0, 0, 14, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(AutoDealsPublisher.channelTitle(item), 16, Theme.key_windowBackgroundWhiteBlackText);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            TextView plan = text(AutoDealsPlanSheet.planLine(item), 13, Theme.key_windowBackgroundWhiteGrayText);
            plan.setSingleLine(true);
            plan.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(plan);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            if (item.optLong("id") == channelId) {
                ImageView check = new ImageView(context);
                check.setImageResource(R.drawable.msg_check_s);
                check.setColorFilter(getThemedColor(Theme.key_featuredStickers_addButton));
                row.addView(check, LayoutHelper.createLinear(24, 24, 10, 0, 0, 0));
            }
            row.setOnClickListener(v -> {
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                switchChannel(item);
            });
            list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        }
        ScrollView scrollList = new ScrollView(context);
        scrollList.addView(list);
        dialog[0] = new AlertDialog.Builder(context, resourcesProvider)
            .setTitle(DealsGramText.get("WhichChannel"))
            .setView(scrollList)
            .setNegativeButton(DealsGramText.get("Cancel"), null)
            .create();
        dialog[0].show();
    }

    /** Shows another channel: its queue, scheduled posts and search; picks and search state start over. */
    private void switchChannel(JSONObject item) {
        long id = item.optLong("id");
        if (id == channelId) {
            return;
        }
        channelId = id;
        dialogId = id + 1000000000000L;
        channel = item;
        updatePill();
        picks.clear();
        refreshPicks();
        filters = new AutoDealsSearchFilters();
        updateSearchChips();
        results.clear();
        facets.clear();
        sourceKeys.clear();
        searchLoaded = false;
        searchHasMore = false;
        searchError = null;
        onlyMine = false;
        ++searchToken;
        searchLoading = false;
        scroll.scrollTo(0, 0);
        load(false);
    }

    private BackupImageView avatar(JSONObject item, int radiusDp) {
        BackupImageView avatar = new BackupImageView(getContext());
        avatar.setRoundRadius(dp(radiusDp));
        org.telegram.ui.Components.AvatarDrawable avatarDrawable = new org.telegram.ui.Components.AvatarDrawable();
        // AutoDeals ids are Bot API ids (-100xxxxxxxxxx); Telegram's chat id is the positive xxxxxxxxxx.
        long chatId = -(item.optLong("id") + 1000000000000L);
        org.telegram.tgnet.TLRPC.Chat chat = org.telegram.messenger.MessagesController.getInstance(account).getChat(chatId);
        if (chat != null) {
            avatarDrawable.setInfo(account, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(chatId, AutoDealsPublisher.channelTitle(item), null);
            avatar.setImageDrawable(avatarDrawable);
        }
        return avatar;
    }

    /** After a change: the search keeps its results and position, the other tabs reload. */
    private void reload() {
        load(tab == TAB_SEARCH);
    }

    // --- Lists -------------------------------------------------------------------

    private void showLoading() {
        list.removeAllViews();
        progress.setVisibility(View.VISIBLE);
        note.setVisibility(View.GONE);
        queueFilter.setVisibility(View.GONE);
    }

    private void render() {
        progress.setVisibility(View.GONE);
        list.removeAllViews();
        // Only warnings (V2 paused, curated-only mode) use the grey line now; the lists explain themselves.
        note.setVisibility(View.GONE);
        queueFilter.setVisibility(View.GONE);
        if (loadError != null) {
            note.setVisibility(View.GONE);
            empty(loadError);
            return;
        }
        boolean locked = channel != null && channel.optJSONObject("subscription") == null;
        queueTab.set(locked ? R.drawable.outline_header_lock_24 : R.drawable.msg_list, DealsGramText.get("TabQueue"));
        scheduledTab.set(locked ? R.drawable.outline_header_lock_24 : R.drawable.msg_calendar2, DealsGramText.get("TabScheduled"));
        if (tab == TAB_SEARCH) {
            note.setVisibility(View.GONE);
            renderSearch();
        } else if (locked) {
            // Free plan or expired: queue and scheduled posts come with a subscription.
            note.setVisibility(View.GONE);
            renderLocked(tab == TAB_SCHEDULED ? AutoDealsPlanSheet.FEATURE_SCHEDULING : AutoDealsPlanSheet.FEATURE_QUEUE);
        } else if (tab == TAB_SCHEDULED) {
            renderScheduled();
        } else if (!v2) {
            note.setVisibility(View.GONE);
            empty(DealsGramText.get("V2Off"));
            action(DealsGramText.get("TurnOnAutoposting"), () -> openSettings(""));
        } else {
            renderQueue();
        }
    }

    private void renderQueue() {
        boolean curatedOnly = config != null && "curated_queue".equals(config.optString("mode"));
        boolean paused = config != null && !config.optBoolean("enabled", true);
        if (paused || nextHour == null) {
            note.setText(DealsGramText.get("V2Paused"));
            note.setVisibility(View.VISIBLE);
            action(DealsGramText.get(paused ? "TurnOnAutoposting" : "OpenTimetables"), () -> openSettings(paused ? "" : "/timetables"));
        } else if (curatedOnly) {
            note.setText(DealsGramText.get("CuratedOnly"));
            note.setVisibility(View.VISIBLE);
        }
        if (!curatedOnly) {
            allChip.setText(DealsGramText.get("QueueAll"));
            mineChip.setText(DealsGramText.format("QueueMine", curated.size()));
            styleChip(allChip, !onlyMine);
            styleChip(mineChip, onlyMine);
            queueFilter.setVisibility(View.VISIBLE);
        }
        boolean mineOnly = curatedOnly || onlyMine;

        List<JSONObject> rows = new ArrayList<>(curated);
        Set<Long> curatedOffers = new HashSet<>();
        for (JSONObject item : curated) {
            curatedOffers.add(item.optLong("offer_id"));
        }
        if (!mineOnly) {
            for (JSONObject item : live) {
                if (!curatedOffers.contains(item.optLong("offer_id"))) {
                    rows.add(item);
                }
            }
        }
        if (rows.isEmpty()) {
            empty(mineOnly ? DealsGramText.get("CuratedEmpty") : DealsGramText.get("QueueEmptyFilters"));
            return;
        }
        List<Date> slots = paused ? new ArrayList<>() : predictSlots(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JSONObject item = rows.get(i);
            boolean mine = i < curated.size();
            Date slot = i < slots.size() ? slots.get(i) : null;
            addRow(slot, item, mine ? DealsGramText.get("QueuedByYou") : null, () -> {
                if (mine) {
                    curatedMenu(item);
                } else {
                    offerMenu(item, true);
                }
            });
        }
    }

    private void renderScheduled() {
        if (scheduled.isEmpty()) {
            empty(DealsGramText.get("NoScheduled"));
            return;
        }
        for (JSONObject post : scheduled) {
            String type = post.optString("type");
            String label = "auto".equals(type) ? DealsGramText.get("Autoposting")
                : ("multi".equals(type) || "summary".equals(type)) ? DealsGramText.get("MultiPost") : null;
            addRow(parse(post.optString("scheduled_at"), TimeZone.getTimeZone(SERVER_TIMEZONE)), post, label, () -> scheduledMenu(post));
        }
    }

    // --- Search -------------------------------------------------------------------

    private String currentQuery() {
        String query = searchField.getText() != null ? searchField.getText().toString().trim() : "";
        return query.length() >= MIN_QUERY ? query : "";
    }

    private void setOnlyMine(boolean value) {
        if (onlyMine != value) {
            onlyMine = value;
            render();
        }
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setTextColor(getThemedColor(on ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
        chip.setBackground(Theme.createRoundRectDrawable(dp(16), getThemedColor(on ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhite)));
    }

    private TextView barChip() {
        TextView chip = new TextView(getContext());
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setBackground(Theme.createRoundRectDrawable(dp(18), getThemedColor(Theme.key_windowBackgroundWhite)));
        return chip;
    }

    private void updateSearchChips() {
        sortChip.setText(DealsGramText.format("SortBy", AutoDealsSearchFilters.sortLabel(filters.sort)) + "  ▾");
        sortChip.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        int count = filters.count();
        filtersChip.setText(count > 0 ? DealsGramText.format("FiltersCount", count) : DealsGramText.get("Filters"));
        boolean on = count > 0;
        filtersChip.setTextColor(getThemedColor(on ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
        filtersChip.setBackground(Theme.createRoundRectDrawable(dp(18),
            getThemedColor(on ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhite)));
    }

    private void pickSort() {
        List<String> keys = new ArrayList<>(AutoDealsSearchFilters.SORTS.keySet());
        CharSequence[] labels = new CharSequence[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String label = AutoDealsSearchFilters.sortLabel(keys.get(i));
            labels[i] = keys.get(i).equals(filters.sort) ? "✓  " + label : label;
        }
        new AlertDialog.Builder(getContext(), resourcesProvider)
            .setTitle(DealsGramText.get("SortTitle"))
            .setItems(labels, (dialog, which) -> {
                filters.sort = keys.get(which);
                updateSearchChips();
                search(false);
            })
            .show();
    }

    private void openFilters() {
        AndroidUtilities.hideKeyboard(searchField);
        new AutoDealsFiltersSheet(getContext(), resourcesProvider, filters, sourceKeys, facets, chosen -> {
            filters = chosen;
            updateSearchChips();
            search(false);
        }).show();
    }

    /**
     * search_autoposting_v2_offers, with the same parameters as the dashboard: without a keyword it browses
     * the recent offers of the channel's sources; filters and sort are applied by the server.
     */
    private void search(boolean append) {
        if (append && (searchLoading || !searchHasMore)) {
            return;
        }
        int token = ++searchToken;
        int page = append ? searchPage + 1 : 0;
        String query = currentQuery();
        AutoDealsSearchFilters sent = filters.copy();
        searchLoading = true;
        searchError = null;
        if (!append) {
            searchedQuery = query;
            results.clear();
            if (tab == TAB_SEARCH) {
                showLoading();
                scroll.scrollTo(0, 0);
            }
        } else {
            addLoadingMore();
        }
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject data = null;
            String error = null;
            try {
                JSONObject params = new JSONObject();
                params.put("channel_id", channelId);
                params.put("page", page);
                params.put("per_page", SEARCH_PAGE);
                if (page == 0) {
                    // Category counts are only needed with the first page.
                    params.put("include_category_facets", true);
                }
                if (!query.isEmpty()) {
                    params.put("q", query);
                }
                sent.putParams(params);
                Object response = AutoDealsApi.getInstance(account).call("search_autoposting_v2_offers", params);
                data = response instanceof JSONObject ? (JSONObject) response : new JSONObject();
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
                error = DealsGramText.get("SearchFailed");
            }
            JSONObject result = data;
            String errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != searchToken) {
                    return;
                }
                searchLoading = false;
                if (errorText != null) {
                    // A failed next page keeps what is already on screen.
                    searchError = append ? null : errorText;
                    searchHasMore = append && searchHasMore;
                    if (append) {
                        removeLoadingMore();
                        BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(errorText).show();
                    } else if (tab == TAB_SEARCH) {
                        render();
                    }
                    return;
                }
                searchLoaded = true;
                searchPage = page;
                searchHasMore = result.optBoolean("has_more");
                List<JSONObject> items = objects(result.optJSONArray("items"));
                // Pages can overlap when offers change in between: each offer is listed once.
                Set<Long> known = new HashSet<>();
                for (JSONObject item : results) {
                    known.add(item.optLong("offer_id"));
                }
                List<JSONObject> added = new ArrayList<>();
                for (JSONObject item : items) {
                    if (known.add(item.optLong("offer_id"))) {
                        added.add(item);
                    }
                }
                results.addAll(added);
                if (page == 0) {
                    if (result.has("category_facets")) {
                        replace(facets, objects(result.optJSONArray("category_facets")));
                    }
                    readSources(result.optJSONObject("config"));
                }
                if (tab != TAB_SEARCH) {
                    return;
                }
                if (append) {
                    removeLoadingMore();
                    progress.setVisibility(View.GONE);
                    for (JSONObject item : added) {
                        addSearchRow(item);
                    }
                } else {
                    render();
                }
            });
        });
    }

    /** Enabled sources of the V2 configuration, the store filter's choices. */
    private void readSources(JSONObject v2Config) {
        if (v2Config == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (JSONObject source : objects(v2Config.optJSONArray("source_configs"))) {
            if (source.optBoolean("enabled")) {
                keys.add(source.optString("store").toUpperCase(Locale.US) + "|" + source.optString("nation").toUpperCase(Locale.US));
            }
        }
        replace(sourceKeys, keys);
    }

    private void renderSearch() {
        pickMarks.clear();
        pickRows.clear();
        if (searchError != null) {
            empty(searchError);
            return;
        }
        if (searchLoading && results.isEmpty()) {
            progress.setVisibility(View.VISIBLE);
            return;
        }
        if (results.isEmpty()) {
            empty(filters.count() > 0 ? DealsGramText.get("NoDealsFilters") : DealsGramText.get("NoDealsSearch"));
            return;
        }
        for (JSONObject item : results) {
            addSearchRow(item);
        }
        if (searchLoading) {
            addLoadingMore();
        }
    }

    private View loadingMore;

    private void addLoadingMore() {
        if (loadingMore != null || tab != TAB_SEARCH) {
            return;
        }
        FrameLayout box = new FrameLayout(getContext());
        RadialProgressView spinner = new RadialProgressView(getContext(), resourcesProvider);
        spinner.setSize(dp(24));
        box.addView(spinner, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
        loadingMore = box;
        list.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
    }

    private void removeLoadingMore() {
        if (loadingMore != null) {
            list.removeView(loadingMore);
            loadingMore = null;
        }
    }

    /**
     * An offer as the dashboard's card shows it: photo, title, price with the struck original and the
     * discount, deal type, store, coupon and the publication badges; the chart opens its price history.
     */
    private void addSearchRow(JSONObject item) {
        Context context = getContext();
        if (list.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(getThemedColor(Theme.key_divider));
            list.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 84, 0, 0, 0));
        }
        LinearLayout row = new LinearLayout(context);
        row.setPadding(dp(12), dp(10), dp(4), dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));
        // As when selecting messages: a long press starts, then a tap adds or removes.
        row.setOnClickListener(v -> {
            if (!picks.isEmpty()) {
                togglePick(item);
            } else {
                searchMenu(item);
            }
        });
        row.setOnLongClickListener(v -> {
            togglePick(item);
            return true;
        });

        FrameLayout thumb = (FrameLayout) thumbnail(item, 60, 12, 28);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.msg_check_s);
        mark.setColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText));
        mark.setScaleType(ImageView.ScaleType.CENTER);
        mark.setBackground(Theme.createCircleDrawable(dp(24), getThemedColor(Theme.key_featuredStickers_addButton)));
        thumb.addView(mark, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.TOP, -4, -4, 0, 0));
        long offerId = item.optLong("offer_id");
        pickMarks.put(offerId, mark);
        pickRows.put(offerId, row);
        showPicked(offerId);
        row.addView(thumb, LayoutHelper.createLinear(60 + 7, 60 + 7, 0, 2, 5, 0));

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(itemTitle(item), 15, Theme.key_windowBackgroundWhiteBlackText);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);

        SpannableStringBuilder prices = new SpannableStringBuilder();
        double price = item.optDouble("deal_price", Double.NaN);
        double original = item.optDouble("original_price", Double.NaN);
        String currency = currencyOf(item);
        if (!Double.isNaN(price)) {
            prices.append(AutoDealsPriceHistorySheet.price(price, currency));
            prices.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_windowBackgroundWhiteBlackText)), 0, prices.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            prices.setSpan(new org.telegram.ui.Components.TypefaceSpan(AndroidUtilities.bold()), 0, prices.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!Double.isNaN(original) && original > price) {
                int start = prices.length() + 2;
                prices.append("  ").append(AutoDealsPriceHistorySheet.price(original, currency));
                prices.setSpan(new StrikethroughSpan(), start, prices.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            double discount = item.optDouble("discount_percentage", Double.NaN);
            if (!Double.isNaN(discount) && discount >= 1) {
                int start = prices.length() + 2;
                prices.append("  -").append(String.valueOf((int) Math.round(discount))).append("%");
                prices.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_windowBackgroundWhiteGreenText)), start, prices.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        TextView priceLine = text(prices, 14, Theme.key_windowBackgroundWhiteGrayText);
        priceLine.setSingleLine(true);
        info.addView(priceLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        TextView meta = text(searchMeta(item), 13, Theme.key_windowBackgroundWhiteGrayText);
        meta.setMaxLines(2);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView chart = new ImageView(context);
        chart.setImageResource(R.drawable.msg_stats);
        chart.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        chart.setScaleType(ImageView.ScaleType.CENTER);
        chart.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlueText), .12f)));
        chart.setOnClickListener(v -> openHistory(item));
        row.addView(chart, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static final java.util.Map<String, Integer> DEAL_COLORS = new java.util.HashMap<>();
    static {
        // DEAL_COLORS of the dashboard (lib/types).
        DEAL_COLORS.put("lightning", 0xffff9500);
        DEAL_COLORS.put("normal", 0xff34c759);
        DEAL_COLORS.put("coupon", 0xffaf52de);
        DEAL_COLORS.put("warehouse", 0xff5856d6);
        DEAL_COLORS.put("error", 0xffff3b30);
        DEAL_COLORS.put("lowest", 0xff007aff);
        DEAL_COLORS.put("nodiscount", 0xff8e8e93);
    }

    /** "Lampo · Amazon IT · Coupon 10% · Pubblicato il 28/10 18:00 · In coda curata". */
    private CharSequence searchMeta(JSONObject item) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String type = item.optString("deal_type", "");
        String typeLabel = AutoDealsSearchFilters.DEAL_TYPES.containsKey(type) ? AutoDealsSearchFilters.dealTypeLabel(type)
            : "nodiscount".equals(type) ? DealsGramText.get("NoDiscount") : null;
        if (typeLabel != null) {
            out.append(typeLabel);
            Integer color = DEAL_COLORS.get(type);
            if (color != null) {
                out.setSpan(new ForegroundColorSpan(color), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        String coupon = item.optString("coupon", "");
        if (!coupon.isEmpty() && !"null".equals(coupon)) {
            separator(out).append(DealsGramText.format("CouponValue", coupon));
        }
        double rating = item.optDouble("reviews_rate", Double.NaN);
        if (!Double.isNaN(rating) && rating > 0) {
            separator(out).append(String.format(DealsGramText.locale(), "%.1f ★", rating));
            int reviews = item.optInt("reviews_number", 0);
            if (reviews > 0) {
                out.append(String.format(DealsGramText.locale(), " (%,d)", reviews));
            }
        }
        for (String badge : publicationBadges(item.optJSONObject("publication_state"))) {
            int start = separator(out).length();
            out.append(badge);
            out.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_windowBackgroundWhiteBlueText)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** publicationBadges() and badgeLabel() of the dashboard (publication-state.ts, offer-publishing.ts). */
    private List<String> publicationBadges(JSONObject state) {
        List<String> badges = new ArrayList<>();
        if (state == null) {
            return badges;
        }
        if (state.optBoolean("posted_today")) {
            badges.add(DealsGramText.format("PublishedTodayAt", formatDate(state.optString("last_posted_at"), "HH:mm")));
        } else if (state.optBoolean("recently_posted")) {
            badges.add(DealsGramText.format("PublishedOn", formatDate(state.optString("last_posted_at"), DealsGramText.get("DateShortTime"))));
        }
        if (state.optBoolean("scheduled")) {
            badges.add(DealsGramText.format("ScheduledOn", formatDate(state.optString("next_scheduled_at"), DealsGramText.get("DateShortTime"))));
        }
        if (state.optBoolean("in_curated_queue")) {
            badges.add(DealsGramText.get("InCuratedQueue"));
        }
        return badges;
    }

    private String formatDate(String serverTime, String pattern) {
        Date date = parse(serverTime, TimeZone.getTimeZone(SERVER_TIMEZONE));
        if (date == null) {
            return "—";
        }
        SimpleDateFormat format = new SimpleDateFormat(pattern, DealsGramText.locale());
        format.setTimeZone(channelTimeZone());
        return format.format(date);
    }

    /** CURRENCY of OfferPriceHistoryService by nation; euro for the rest. */
    private static String currencyOf(JSONObject item) {
        switch (item.optString("nation", "").toUpperCase(Locale.US)) {
            case "GB":
            case "UK":
            case "CO.UK":
                return "GBP";
            case "US":
            case "COM":
                return "USD";
            case "IL":
                return "ILS";
            default:
                return "EUR";
        }
    }

    /** The dashboard's publish menu: publish, queue (with V2 on), schedule, plus the price history. */
    private void searchMenu(JSONObject item) {
        long offerId = item.optLong("offer_id");
        boolean manual = channel != null && AutoDealsPublisher.canPublish(channel);
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (manual) {
            labels.add(DealsGramText.get("PublishNow"));
            icons.add(R.drawable.msg_send);
            actions.add(() -> offerAction(offerId, "publish_now", null, false));
        }
        if (v2) {
            labels.add(DealsGramText.get("AddToQueueMenu"));
            icons.add(R.drawable.msg_list);
            actions.add(() -> offerAction(offerId, "add_curated", null, false));
        }
        // What the plan does not include stays in the menu with a lock and opens the plan sheet.
        if (manual && AutoDealsPublisher.canSchedule(channel)) {
            labels.add(DealsGramText.get("ScheduleMenu"));
            icons.add(R.drawable.msg_calendar2);
            actions.add(() -> pickTime(0, iso -> offerAction(offerId, "schedule", iso, false)));
        } else if (channel != null) {
            labels.add(DealsGramText.get("ScheduleMenu"));
            icons.add(R.drawable.outline_header_lock_24);
            actions.add(() -> AutoDealsPlanSheet.showFeature(getContext(), resourcesProvider, AutoDealsPlanSheet.FEATURE_SCHEDULING, channelId));
        }
        if (canMulti(item)) {
            labels.add(DealsGramText.get("MultiPostMenu"));
            icons.add(R.drawable.msg_check_s);
            actions.add(() -> togglePick(item));
        } else if (!multiAllowed() && "AZ".equalsIgnoreCase(item.optString("store"))) {
            labels.add(DealsGramText.get("MultiPostMenu"));
            icons.add(R.drawable.outline_header_lock_24);
            actions.add(() -> AutoDealsPlanSheet.showFeature(getContext(), resourcesProvider, AutoDealsPlanSheet.FEATURE_MULTI, channelId));
        }
        labels.add(DealsGramText.get("PriceHistory"));
        icons.add(R.drawable.msg_stats);
        actions.add(() -> openHistory(item));
        menu(itemTitle(item), labels, icons, actions);
    }

    // --- Multi post selection ----------------------------------------------------------

    private boolean multiAllowed() {
        JSONObject subscription = channel != null ? channel.optJSONObject("subscription") : null;
        return subscription != null && subscription.optBoolean("multi_posts", true);
    }

    /** Amazon only for now, as isMultiStore() on the dashboard: create_multi_post builds other links as AliExpress. */
    private boolean canMulti(JSONObject item) {
        return multiAllowed() && "AZ".equalsIgnoreCase(item.optString("store"));
    }

    private int pickIndex(long offerId) {
        for (int i = 0; i < picks.size(); i++) {
            if (picks.get(i).optLong("offer_id") == offerId) {
                return i;
            }
        }
        return -1;
    }

    private void togglePick(JSONObject item) {
        long offerId = item.optLong("offer_id");
        int index = pickIndex(offerId);
        if (index >= 0) {
            picks.remove(index);
        } else if (!multiAllowed()) {
            AutoDealsPlanSheet.showFeature(getContext(), resourcesProvider, AutoDealsPlanSheet.FEATURE_MULTI, channelId);
            return;
        } else if (!canMulti(item)) {
            BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(DealsGramText.get("MultiAmazonOnly")).show();
            return;
        } else if (picks.size() >= AutoDealsMultiSheet.MAX) {
            BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(DealsGramText.format("MaxInMultiSentence", AutoDealsMultiSheet.MAX)).show();
            return;
        } else {
            picks.add(item);
            if (picks.size() == 1) {
                BulletinFactory.of(bulletinContainer, resourcesProvider).createSimpleBulletin(R.raw.info,
                    DealsGramText.get("PickedHint")).show();
            }
        }
        showPicked(offerId);
        refreshPicks();
    }

    private void showPicked(long offerId) {
        boolean on = pickIndex(offerId) >= 0;
        View mark = pickMarks.get(offerId);
        if (mark != null) {
            mark.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        View row = pickRows.get(offerId);
        if (row != null) {
            row.setBackgroundColor(on ? Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .10f) : 0);
            if (!on) {
                row.setBackground(Theme.getSelectorDrawable(false));
            }
        }
    }

    /** Check marks on screen and the bottom buttons, after any change of the selection. */
    private void refreshPicks() {
        for (Long offerId : pickMarks.keySet()) {
            showPicked(offerId);
        }
        boolean selecting = !picks.isEmpty();
        addButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        clearButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        multiButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        multiButton.setText(DealsGramText.format("MultiPostCount", picks.size()), true);
        multiButton.setEnabled(picks.size() >= AutoDealsMultiSheet.MIN);
    }

    private void openMulti() {
        AndroidUtilities.hideKeyboard(searchField);
        new AutoDealsMultiSheet(getContext(), resourcesProvider, dialogId, channelId, picks, v2, null, this::refreshPicks, done -> {
            picks.clear();
            refreshPicks();
            BulletinFactory.of(bulletinContainer, resourcesProvider).createSimpleBulletin(R.raw.contact_check, done).show();
            reload();
        }).show();
    }

    private void openHistory(JSONObject item) {
        AndroidUtilities.hideKeyboard(searchField);
        new AutoDealsPriceHistorySheet(getContext(), resourcesProvider, channelId, item).show();
    }

    /** What the subscription adds in place of the list, with the way to the plans. */
    private void renderLocked(String feature) {
        Context context = getContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(28), dp(24), dp(24));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(AutoDealsPlanSheet.FEATURE_SCHEDULING.equals(feature) ? R.drawable.msg_calendar2 : R.drawable.msg_list);
        icon.setColorFilter(getThemedColor(Theme.key_featuredStickers_addButton));
        icon.setBackground(Theme.createCircleDrawable(dp(56), Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .12f)));
        box.addView(icon, LayoutHelper.createLinear(56, 56));
        String key = AutoDealsPlanSheet.FEATURE_SCHEDULING.equals(feature) ? "FeatureSchedule" : "FeatureQueue";
        TextView title = text(DealsGramText.get(key + "Title"), 16, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        TextView body = text(DealsGramText.get(key + "Text"), 14, Theme.key_windowBackgroundWhiteGrayText);
        body.setGravity(Gravity.CENTER);
        box.addView(body, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 16));
        ButtonWithCounterView open = new ButtonWithCounterView(context, resourcesProvider).setRound();
        open.setText(DealsGramText.get("DiscoverSubscription"), false);
        open.setOnClickListener(v -> AutoDealsPlanSheet.openCheckout(getContext(), channelId));
        box.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        list.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** The channel's Autoposting V2 pages of the mini-app ("" = its home, "/timetables", …). */
    private void openSettings(String subPath) {
        // The mini-app sheet opens over the chat, so this sheet closes first.
        dismiss();
        AutoDealsPublisher.openMiniApp(fragment, "/channel/" + channelId + "/autoposting-v2" + subPath);
    }

    /** A button in the list that opens what fixes the message above it. */
    private void action(String label, Runnable onClick) {
        ButtonWithCounterView button = new ButtonWithCounterView(getContext(), false, resourcesProvider).setRound();
        button.setText(label, false);
        button.setOnClickListener(v -> onClick.run());
        list.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 24, 0, 24, 16));
    }

    private void empty(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        list.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 48, 24, 48));
    }

    /** Time column (when there is one), photo, title, price and an optional label in the accent colour. */
    private void addRow(Date time, JSONObject item, String label, Runnable onClick) {
        Context context = getContext();
        if (list.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(getThemedColor(Theme.key_divider));
            list.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, time != null ? 88 : 16, 0, 0, 0));
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(v -> onClick.run());

        if (time != null) {
            LinearLayout when = new LinearLayout(context);
            when.setOrientation(LinearLayout.VERTICAL);
            when.setGravity(Gravity.CENTER_HORIZONTAL);
            SimpleDateFormat hourFormat = new SimpleDateFormat("HH:mm", DealsGramText.locale());
            hourFormat.setTimeZone(channelTimeZone());
            TextView hour = text(hourFormat.format(time), 15, Theme.key_windowBackgroundWhiteBlackText);
            hour.setTypeface(AndroidUtilities.bold());
            when.addView(hour);
            when.addView(text(dayLabel(time, channelTimeZone()), 12, Theme.key_windowBackgroundWhiteGrayText));
            row.addView(when, LayoutHelper.createLinear(62, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));
        }

        row.addView(thumbnail(item, 44, 10, 22), LayoutHelper.createLinear(44 + 5, 44 + 5, 0, 0, 7, 0));

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(itemTitle(item), 15, Theme.key_windowBackgroundWhiteBlackText);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        // The label is a view of its own: inside an ellipsized TextView a ReplacementSpan receives
        // U+FFFC placeholders instead of its text (a row of "OBJ" boxes when "Von dir eingereiht · …"
        // did not fit), so only the prices after it are ellipsized.
        LinearLayout detailsLine = new LinearLayout(context);
        detailsLine.setGravity(Gravity.CENTER_VERTICAL);
        boolean hasLabel = !TextUtils.isEmpty(label) && !"null".equals(label);
        if (hasLabel) {
            int accent = getThemedColor(Theme.key_featuredStickers_addButton);
            TextView pill = new TextView(context);
            pill.setText(label);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            pill.setTypeface(AndroidUtilities.bold());
            pill.setTextColor(accent);
            pill.setSingleLine(true);
            pill.setPadding(dp(6), dp(1), dp(6), dp(1));
            pill.setBackground(Theme.createRoundRectDrawable(dp(6), Theme.multAlpha(accent, .14f)));
            detailsLine.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        CharSequence rest = details(item, null);
        if (rest.length() > 0) {
            TextView details = text(hasLabel ? TextUtils.concat(" · ", rest) : rest, 13, Theme.key_windowBackgroundWhiteGrayText);
            details.setSingleLine(true);
            details.setEllipsize(TextUtils.TruncateAt.END);
            detailsLine.addView(details, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        info.addView(detailsLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.ic_ab_other);
        more.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon));
        more.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(more, LayoutHelper.createLinear(32, 32, 4, 0, 0, 0));

        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static final java.util.Map<String, Integer> STORE_LOGOS = new java.util.HashMap<>();
    static {
        // STORE_ICONS of the dashboard (lib/types), exported by android/store-icons/export.mjs.
        STORE_LOGOS.put("AZ", R.drawable.dealsgram_store_amazon);
        STORE_LOGOS.put("AE", R.drawable.dealsgram_store_aliexpress);
        STORE_LOGOS.put("COMET", R.drawable.dealsgram_store_comet);
        STORE_LOGOS.put("OTTO", R.drawable.dealsgram_store_otto);
        STORE_LOGOS.put("UNIEURO", R.drawable.dealsgram_store_unieuro);
        STORE_LOGOS.put("FUXTEC", R.drawable.dealsgram_store_fuxtec);
        STORE_LOGOS.put("MEDIAWORLD", R.drawable.dealsgram_store_mediaworld);
        STORE_LOGOS.put("TOYSCENTER", R.drawable.dealsgram_store_toyscenter);
    }

    /** Product thumbnail with its store badge. */
    private View thumbnail(JSONObject item, int size, int radius, int badge) {
        Context context = getContext();
        FrameLayout frame = new FrameLayout(context);
        BackupImageView image = new BackupImageView(context);
        image.setRoundRadius(dp(radius));
        image.setBackground(Theme.createRoundRectDrawable(dp(radius), getThemedColor(Theme.key_windowBackgroundGray)));
        String photo = item.optString("image", "");
        if (photo.startsWith("http")) {
            image.setImage(photo, (size * 2) + "_" + (size * 2), null);
        }
        frame.addView(image, LayoutHelper.createFrame(size, size, Gravity.LEFT | Gravity.TOP));
        Integer logo = STORE_LOGOS.get(item.optString("store", "").toUpperCase(Locale.US));
        if (logo != null) {
            ImageView mark = new ImageView(context);
            mark.setImageResource(logo);
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(badge * 0.18f);
            mark.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
            mark.setBackground(Theme.createRoundRectDrawable(dp(Math.round(badge / 3f)), 0xffffffff));
            mark.setElevation(dp(2));
            frame.addView(mark, LayoutHelper.createFrame(badge, badge, Gravity.RIGHT | Gravity.BOTTOM));
        }
        frame.setClipChildren(false);
        return frame;
    }

    private TextView text(CharSequence value, int size, int colorKey) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(getThemedColor(colorKey));
        return view;
    }

    /** Products of a multi post, 0 for a single offer. */
    private static int multiCount(JSONObject item) {
        JSONObject multi = item.optJSONObject("automatic_multi");
        JSONArray members = multi != null ? multi.optJSONArray("members") : null;
        if (members != null && members.length() > 1) {
            return members.length();
        }
        return item.optBoolean("is_multi_post") || "multi".equals(item.optString("deal_type")) ? 1 : 0;
    }

    private static String itemTitle(JSONObject item) {
        int multi = multiCount(item);
        if (multi > 1) {
            return DealsGramText.format("MultiPostProducts", multi);
        }
        String title = item.optString("title", "");
        if (TextUtils.isEmpty(title) || "null".equals(title)) {
            return DealsGramText.get("UntitledDeal");
        }
        // Some titles arrive with HTML entities (5.8&quot;).
        return title.indexOf('&') >= 0 ? android.text.Html.fromHtml(title, android.text.Html.FROM_HTML_MODE_LEGACY).toString() : title;
    }

    /** "39,99 € · -43% · minimo storico", after the row's label (drawn by addRow as its own pill). */
    private CharSequence details(JSONObject item, String label) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        double price = item.optDouble("deal_price", Double.NaN);
        // A multi post has no single price: the row's price is only its first product's.
        if (!Double.isNaN(price) && price > 0 && multiCount(item) == 0) {
            separator(out).append(String.format(DealsGramText.locale(), "%.2f €", price));
            double discount = item.optDouble("discount_percentage", Double.NaN);
            if (!Double.isNaN(discount) && discount >= 1) {
                separator(out).append("-").append(String.valueOf((int) Math.round(discount))).append("%");
            }
            double lowest = item.optDouble("historical_lowest_price", Double.NaN);
            if (!Double.isNaN(lowest) && lowest > 0 && price <= lowest) {
                separator(out).append(DealsGramText.get("AllTimeLowLower"));
            }
        }
        if ("processing".equals(item.optString("status"))) {
            separator(out).append(DealsGramText.get("Publishing"));
        }
        return out;
    }

    private static SpannableStringBuilder separator(SpannableStringBuilder out) {
        if (out.length() > 0) {
            out.append(" · ");
        }
        return out;
    }

    // --- Actions -----------------------------------------------------------------

    private void offerMenu(JSONObject item, boolean inQueue) {
        long offerId = item.optLong("offer_id");
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(DealsGramText.get("PublishNow"));
        icons.add(R.drawable.msg_send);
        actions.add(() -> offerAction(offerId, "publish_now", null, false));
        labels.add(DealsGramText.get("AddToQueue"));
        icons.add(R.drawable.msg_list);
        actions.add(() -> offerAction(offerId, "add_curated", null, false));
        labels.add(DealsGramText.get("ScheduleMenu"));
        icons.add(R.drawable.msg_calendar2);
        actions.add(() -> pickTime(0, iso -> offerAction(offerId, "schedule", iso, false)));
        if (inQueue) {
            // As the dashboard's queue: each one asks for a confirmation first.
            labels.add(DealsGramText.get("Skip24h"));
            icons.add(R.drawable.msg_go_down);
            actions.add(() -> confirmOfferAction(offerId, "skip_24h", "ConfirmSkipTitle", "ConfirmSkipText", "Skip24h", false));
            labels.add(DealsGramText.get("BanTemp"));
            icons.add(R.drawable.msg_mute);
            actions.add(() -> confirmOfferAction(offerId, "ban_temp", "ConfirmBanTempTitle", "ConfirmBanTempText", "BanTemp", false));
            labels.add(DealsGramText.get("BanForever"));
            icons.add(R.drawable.msg_block);
            actions.add(() -> confirmOfferAction(offerId, "ban_forever", "ConfirmBanForeverTitle", "ConfirmBanForeverText", "BanForever", true));
        }
        menu(itemTitle(item), labels, icons, actions);
    }

    /** Confirmation before an action that removes the offer from the queue; the permanent ban in red. */
    private void confirmOfferAction(long offerId, String action, String titleKey, String textKey, String buttonKey, boolean destructive) {
        AlertDialog dialog = new AlertDialog.Builder(getContext(), resourcesProvider)
            .setTitle(DealsGramText.get(titleKey))
            .setMessage(DealsGramText.get(textKey))
            .setPositiveButton(DealsGramText.get(buttonKey), (d, w) -> offerAction(offerId, action, null, false))
            .setNegativeButton(DealsGramText.get("Cancel"), null)
            .create();
        dialog.show();
        if (destructive) {
            dialog.redPositive();
        }
    }

    private void curatedMenu(JSONObject item) {
        long queueId = item.optLong("queue_id");
        int index = curated.indexOf(item);
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (index > 0) {
            labels.add(DealsGramText.get("MoveUpQueue"));
            icons.add(R.drawable.msg_go_up);
            actions.add(() -> curatedAction(queueId, "move_up"));
        }
        if (index >= 0 && index < curated.size() - 1) {
            labels.add(DealsGramText.get("MoveDownQueue"));
            icons.add(R.drawable.msg_go_down);
            actions.add(() -> curatedAction(queueId, "move_down"));
        }
        long offerId = item.optLong("offer_id");
        if (offerId > 0) {
            labels.add(DealsGramText.get("PublishNow"));
            icons.add(R.drawable.msg_send);
            actions.add(() -> offerAction(offerId, "publish_now", null, false));
        }
        labels.add(DealsGramText.get("RemoveFromQueue"));
        icons.add(R.drawable.msg_delete);
        actions.add(() -> curatedAction(queueId, "remove"));
        menu(itemTitle(item), labels, icons, actions);
    }

    private void scheduledMenu(JSONObject post) {
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(DealsGramText.get("ChangeTime"));
        icons.add(R.drawable.msg_calendar2);
        Date current = parse(post.optString("scheduled_at"), TimeZone.getTimeZone(SERVER_TIMEZONE));
        int currentSeconds = current != null ? (int) (current.getTime() / 1000) : 0;
        actions.add(() -> pickTime(currentSeconds, iso -> postAction("reschedule_post", post, iso, DealsGramText.get("TimeChanged"))));
        labels.add(DealsGramText.get("Delete"));
        icons.add(R.drawable.msg_delete);
        actions.add(() -> {
            AlertDialog dialog = new AlertDialog.Builder(getContext(), resourcesProvider)
                .setTitle(DealsGramText.get("DeleteScheduledTitle"))
                .setMessage(DealsGramText.format("WontBePublished", itemTitle(post)))
                .setPositiveButton(DealsGramText.get("Delete"), (d, w) -> postAction("delete_post", post, null, DealsGramText.get("PostDeleted")))
                .setNegativeButton(DealsGramText.get("Cancel"), null)
                .create();
            dialog.show();
            dialog.redPositive();
        });
        menu(itemTitle(post), labels, icons, actions);
    }

    private void menu(CharSequence title, List<CharSequence> labels, List<Integer> icons, List<Runnable> actions) {
        int[] iconIds = new int[icons.size()];
        for (int i = 0; i < iconIds.length; i++) {
            iconIds[i] = icons.get(i);
        }
        new AlertDialog.Builder(getContext(), resourcesProvider)
            .setTitle(title)
            .setItems(labels.toArray(new CharSequence[0]), iconIds, (dialog, which) -> actions.get(which).run())
            .show();
    }

    private void pickTime(int current, Utilities.Callback<String> onPicked) {
        AlertsCreator.createScheduleDatePickerDialog(getContext(), dialogId, current, 0,
            (notify, date, repeat) -> onPicked.run(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(date * 1000L))),
            null, resourcesProvider).show();
    }

    /** publish_now, add_curated, schedule and skip_24h on an offer; recent products need a confirmation. */
    private void offerAction(long offerId, String action, String scheduledAt, boolean confirmDuplicates) {
        run(api -> {
            JSONObject params = new JSONObject();
            params.put("channel_id", channelId);
            params.put("offer_id", offerId);
            params.put("offer_action", action);
            if ("ban_temp".equals(action)) {
                params.put("ban_days", 30);
            }
            if (scheduledAt != null) {
                params.put("scheduled_at", scheduledAt);
            }
            if (confirmDuplicates) {
                params.put("confirm_duplicates", true);
            }
            api.call("autoposting_v2_offer_action", params, ORIGIN);
            switch (action) {
                case "publish_now": return DealsGramText.get("DonePublishing");
                case "add_curated": return DealsGramText.get("DoneQueued");
                case "schedule": return DealsGramText.get("DoneScheduled");
                case "ban_temp": return DealsGramText.get("DoneBanTemp");
                case "ban_forever": return DealsGramText.get("DoneBanForever");
                default: return DealsGramText.get("DoneSkipped");
            }
        }, e -> {
            if (!"duplicate_product_confirmation_required".equals(e.code)) {
                return false;
            }
            new AlertDialog.Builder(getContext(), resourcesProvider)
                .setTitle(DealsGramText.get("RecentlyPublished"))
                .setMessage(DealsGramText.get("DuplicateHere"))
                .setPositiveButton(DealsGramText.get("Continue"), (d, w) -> offerAction(offerId, action, scheduledAt, true))
                .setNegativeButton(DealsGramText.get("Cancel"), null)
                .show();
            return true;
        });
    }

    private void curatedAction(long queueId, String action) {
        run(api -> {
            JSONObject params = new JSONObject();
            params.put("channel_id", channelId);
            params.put("queue_id", queueId);
            params.put("queue_action", action);
            api.call("update_autoposting_v2_curated_queue", params, ORIGIN);
            return "remove".equals(action) ? DealsGramText.get("RemovedFromQueue") : null;
        }, null);
    }

    private void postAction(String action, JSONObject post, String scheduledAt, String done) {
        run(api -> {
            JSONObject params = new JSONObject();
            params.put("channel_id", channelId);
            params.put("type", post.optString("type"));
            params.put("ref_id", post.optLong("ref_id"));
            if (scheduledAt != null) {
                params.put("scheduled_at", scheduledAt);
            }
            api.call(action, params);
            return done;
        }, null);
    }

    private interface ApiWork {
        String run(AutoDealsApi api) throws Exception;
    }

    private interface ErrorHandler {
        boolean handle(AutoDealsApi.AutoDealsException e);
    }

    /** Runs a change with a spinner, then reloads every list, since a change moves the others too. */
    private void run(ApiWork work, ErrorHandler onError) {
        AlertDialog spinner = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER, resourcesProvider);
        spinner.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            String done = null;
            AutoDealsApi.AutoDealsException failure = null;
            try {
                done = work.run(AutoDealsApi.getInstance(account));
            } catch (AutoDealsApi.AutoDealsException e) {
                failure = e;
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
                failure = new AutoDealsApi.AutoDealsException("unknown", DealsGramText.get("SomethingWrong"));
            }
            String doneText = done;
            AutoDealsApi.AutoDealsException error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                spinner.dismiss();
                if (error != null) {
                    if (onError == null || !onError.handle(error)) {
                        BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(error.text).show();
                    }
                    return;
                }
                if (doneText != null) {
                    BulletinFactory.of(bulletinContainer, resourcesProvider).createSimpleBulletin(R.raw.contact_check, doneText).show();
                }
                reload();
            });
        });
    }

    // --- Expected times ------------------------------------------------------------

    /** Estimate the next display slots from the channel timetable. */
    private List<Date> predictSlots(int count) {
        List<Date> slots = new ArrayList<>();
        if (config == null || nextHour == null) {
            return slots;
        }
        TimeZone tz = TimeZone.getTimeZone(config.optString("timezone", SERVER_TIMEZONE));
        Date first = parse(nextHour.optString("local_time"), tz);
        if (first == null) {
            return slots;
        }
        Object timetables = activeTimetables(first, tz);
        int minInterval = Math.max(1, config.optInt("min_timetable_interval_minutes", 15));
        long collision = Math.max(30, Math.min(120, minInterval * 60 / 2)) * 1000L;
        boolean onlyAutoBlocks = config.optBoolean("post_if_manual_scheduled");
        List<Long> taken = new ArrayList<>();
        for (JSONObject post : scheduled) {
            if (onlyAutoBlocks && !"auto".equals(post.optString("type"))) {
                continue;
            }
            Date at = parse(post.optString("scheduled_at"), TimeZone.getTimeZone(SERVER_TIMEZONE));
            if (at != null) {
                taken.add(at.getTime());
            }
        }

        Calendar day = Calendar.getInstance(tz);
        day.setTime(first);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 30 && slots.size() < count; i++) {
            for (String hour : hours(timetables, weekday(day))) {
                Calendar at = (Calendar) day.clone();
                at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hour.substring(0, 2)));
                at.set(Calendar.MINUTE, Integer.parseInt(hour.substring(3, 5)));
                long time = at.getTimeInMillis();
                if (time < first.getTime() || near(time, taken, collision)) {
                    continue;
                }
                slots.add(new Date(time));
                taken.add(time);
                if (slots.size() >= count) {
                    break;
                }
            }
            day.add(Calendar.DAY_OF_YEAR, 1);
        }
        return slots;
    }

    /** The event timetable when next_hour belongs to it and not to the normal one. */
    private Object activeTimetables(Date first, TimeZone tz) {
        Object normal = config.opt("timetables");
        Object event = config.opt("timetables_event");
        if (!config.optBoolean("event_mode_active") || event == null) {
            return normal;
        }
        Calendar at = Calendar.getInstance(tz);
        at.setTime(first);
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.US);
        format.setTimeZone(tz);
        String hour = format.format(first);
        boolean inEvent = hours(event, weekday(at)).contains(hour);
        boolean inNormal = hours(normal, weekday(at)).contains(hour);
        return inEvent && !inNormal ? event : normal;
    }

    /** Monday 1 … Sunday 7, matching the API timetable keys. */
    private static int weekday(Calendar day) {
        return ((day.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1;
    }

    /** "HH:MM" hours of a day, sorted; key 0 is the API fallback for every day. */
    private static List<String> hours(Object timetables, int weekday) {
        Object table = null;
        if (timetables instanceof JSONObject) {
            JSONObject byDay = (JSONObject) timetables;
            table = byDay.has(String.valueOf(weekday)) ? byDay.opt(String.valueOf(weekday)) : byDay.opt("0");
        } else if (timetables instanceof JSONArray) {
            JSONArray byDay = (JSONArray) timetables;
            table = weekday < byDay.length() ? byDay.opt(weekday) : byDay.opt(0);
        }
        List<String> result = new ArrayList<>();
        if (table instanceof JSONObject) {
            Iterator<String> keys = ((JSONObject) table).keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.matches("\\d{2}:\\d{2}")) {
                    result.add(key);
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    private static boolean near(long time, List<Long> taken, long window) {
        for (long other : taken) {
            if (Math.abs(other - time) <= window) {
                return true;
            }
        }
        return false;
    }

    // --- Helpers -----------------------------------------------------------------

    private static Date parse(String value, TimeZone tz) {
        if (TextUtils.isEmpty(value) || "null".equals(value)) {
            return null;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            format.setTimeZone(tz);
            return format.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** Times are shown in the channel's timezone, as in the bot and the dashboard. */
    private TimeZone channelTimeZone() {
        return TimeZone.getTimeZone(config != null ? config.optString("timezone", SERVER_TIMEZONE) : SERVER_TIMEZONE);
    }

    /** "oggi", "domani" or "sab 2 nov". */
    private static String dayLabel(Date time, TimeZone tz) {
        Calendar today = Calendar.getInstance(tz);
        Calendar at = Calendar.getInstance(tz);
        at.setTime(time);
        if (at.get(Calendar.YEAR) == today.get(Calendar.YEAR) && at.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return DealsGramText.get("Today");
        }
        today.add(Calendar.DAY_OF_YEAR, 1);
        if (at.get(Calendar.YEAR) == today.get(Calendar.YEAR) && at.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return DealsGramText.get("Tomorrow");
        }
        SimpleDateFormat format = new SimpleDateFormat(DealsGramText.get("DateWeekdayDayMonth"), DealsGramText.locale());
        format.setTimeZone(tz);
        return format.format(time);
    }

    private static List<JSONObject> objects(Object data) {
        List<JSONObject> result = new ArrayList<>();
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static <T> void replace(List<T> target, List<T> items) {
        target.clear();
        target.addAll(items);
    }
}
