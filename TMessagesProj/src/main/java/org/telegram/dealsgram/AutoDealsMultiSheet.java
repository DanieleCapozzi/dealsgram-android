package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Html;
import android.text.TextUtils;
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
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.AIEditorAlert;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Compose a multi post from selected offers or product links. */
public class AutoDealsMultiSheet extends BottomSheet {

    private ScrollView scroll;

    /** DealsGram: a swipe down closes the sheet only from the top of the list; elsewhere it scrolls the list. */
    @Override
    protected boolean canDismissWithSwipe() {
        return scroll == null || !scroll.canScrollVertically(-1);
    }

    public static final int MIN = 2;
    public static final int MAX = 10;
    private static final int MODE_NOW = 0;
    private static final int MODE_SCHEDULE = 1;
    private static final int MODE_QUEUE = 2;

    private final int account;
    private final long dialogId;
    private final long channelId;
    /** Shared with the search: removing a product here unpicks it there too. */
    private final List<JSONObject> picks;
    private final Runnable onPicksChanged;
    private final Utilities.Callback<String> onDone;

    private final AIEditorAlert.Tabs tabs;
    private final EditTextCell titleCell;
    private final LinearLayout productList;
    private final EditTextCell linkCell;
    private final ButtonWithCounterView addLink;
    private final ButtonWithCounterView button;
    private final FrameLayout bulletinContainer;
    private int mode = MODE_NOW;
    private int scheduleAt;
    private boolean busy;
    /** In queue mode, the draft already created when the queue asked for a confirmation. */
    private long draftId;
    /** How the user opened it (menu ⋮, links sent, a post's menu), for the "client" block. */
    private AutoDealsApi.Origin origin = new AutoDealsApi.Origin("multi", "chat_menu");

    AutoDealsMultiSheet from(String entry) {
        origin = new AutoDealsApi.Origin("multi", entry);
        return this;
    }

    public AutoDealsMultiSheet(Context context, Theme.ResourcesProvider resourcesProvider, long dialogId, long channelId,
                               List<JSONObject> picks, boolean queueAvailable, List<String> links,
                               Runnable onPicksChanged, Utilities.Callback<String> onDone) {
        super(context, true, resourcesProvider);
        this.account = UserConfig.selectedAccount;
        this.dialogId = dialogId;
        this.channelId = channelId;
        this.picks = picks;
        this.onPicksChanged = onPicksChanged;
        this.onDone = onDone;

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
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(6 + 48 + 12 + 8));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout header = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setText(DealsGramText.get("MultiPost"));
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 22, 0, 0, 0));
        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), .10f)));
        ScaleStateListAnimator.apply(close, .1f, 1.5f);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));

        tabs = new AIEditorAlert.Tabs(context, account, false, resourcesProvider);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite)));
        tabs.setRoundRadius(28);
        tabs.addTab(R.drawable.msg_send, DealsGramText.get("PublishNow"), this::setMode);
        tabs.addTab(R.drawable.msg_calendar2, DealsGramText.get("Schedule"), this::setMode);
        if (queueAvailable) {
            tabs.addTab(R.drawable.msg_list, DealsGramText.get("InQueue"), this::setMode);
        }
        tabs.selectTab(MODE_NOW, false);
        column.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 12, 0, 12, 10));

        titleCell = new EditTextCell(context, DealsGramText.get("MultiTitleHint"), false, false, 120, resourcesProvider);
        titleCell.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        titleCell.editText.setPadding(dp(16), dp(15), dp(16 + 30), dp(15));
        column.addView(titleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));

        // Links: one or several pasted at once.
        LinearLayout linkBox = new LinearLayout(context);
        linkBox.setGravity(Gravity.CENTER_VERTICAL);
        linkBox.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        linkCell = new EditTextCell(context, DealsGramText.get("PasteAmazonLinks"), true, false, -1, resourcesProvider);
        linkCell.editText.setPadding(dp(16), dp(15), dp(8), dp(15));
        linkCell.editText.setMaxLines(4);
        linkBox.addView(linkCell, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        addLink = new ButtonWithCounterView(context, resourcesProvider).setRound();
        addLink.setText(DealsGramText.get("Add"), false);
        addLink.setOnClickListener(v -> {
            AndroidUtilities.hideKeyboard(linkCell.editText);
            addLinks(AutoDealsPublisher.findLinks(linkCell.getText()));
        });
        linkBox.addView(addLink, LayoutHelper.createLinear(96, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        column.addView(linkBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));

        productList = new LinearLayout(context);
        productList.setOrientation(LinearLayout.VERTICAL);
        productList.setPadding(0, dp(6), 0, dp(6));
        productList.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        column.addView(productList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setPadding(dp(12), dp(6), dp(12), dp(12));
        buttons.setBackgroundColor(gray);
        button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setOnClickListener(v -> create(false));
        buttons.addView(button, LayoutHelper.createLinear(0, 48, 1f, Gravity.FILL));
        root.addView(buttons, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        bulletinContainer = new FrameLayout(context);
        root.addView(bulletinContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.BOTTOM, 0, 0, 0, 66));

        setCustomView(root);
        buildProducts();
        updateButton();
        if (links != null && !links.isEmpty()) {
            addLinks(links);
        } else if (picks.isEmpty()) {
            String copied = AutoDealsPublisher.clipboardLink(context);
            if (copied != null) {
                linkCell.setText(copied);
            }
        }
    }

    /** Reads each link with scrape_url and adds its offer; the ones that cannot go in are listed. */
    private void addLinks(List<String> links) {
        if (links.isEmpty()) {
            BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(DealsGramText.get("PasteLink")).show();
            return;
        }
        addLink.setLoading(true);
        addLink.setEnabled(false);
        Utilities.globalQueue.postRunnable(() -> {
            List<JSONObject> found = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            AutoDealsApi api = AutoDealsApi.getInstance(account);
            for (String link : links) {
                try {
                    JSONObject product = AutoDealsPublisher.scrape(api, channelId, link, origin);
                    if (product.optLong("offer_id") <= 0) {
                        problems.add(DealsGramText.format("LinkNotInCatalog", shortLink(link)));
                    } else if (!"AZ".equalsIgnoreCase(product.optString("store"))) {
                        problems.add(DealsGramText.format("LinkAmazonOnly", shortLink(link)));
                    } else {
                        double deal = product.optDouble("deal_price", Double.NaN);
                        double original = product.optDouble("original_price", Double.NaN);
                        if (!Double.isNaN(deal) && !Double.isNaN(original) && original > deal) {
                            product.put("discount_percentage", 100 - Math.floor(deal * 100 / original));
                        }
                        found.add(product);
                    }
                } catch (AutoDealsApi.AutoDealsException e) {
                    problems.add(shortLink(link) + ": " + e.text);
                } catch (Exception e) {
                    problems.add(DealsGramText.format("LinkNotRead", shortLink(link)));
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                addLink.setLoading(false);
                addLink.setEnabled(true);
                for (JSONObject product : found) {
                    boolean known = false;
                    for (JSONObject pick : picks) {
                        known |= pick.optLong("offer_id") == product.optLong("offer_id");
                    }
                    if (known) {
                        problems.add(DealsGramText.format("AlreadyInList", product.optString("title")));
                    } else if (picks.size() >= MAX) {
                        problems.add(DealsGramText.format("MaxInMulti", MAX));
                        break;
                    } else {
                        picks.add(product);
                    }
                }
                if (problems.isEmpty()) {
                    linkCell.setText("");
                } else {
                    BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(TextUtils.join(" · ", problems)).show();
                }
                onPicksChanged.run();
                buildProducts();
                updateButton();
            });
        });
    }

    private static String shortLink(String link) {
        android.net.Uri uri = android.net.Uri.parse(link);
        String label = (uri.getHost() != null ? uri.getHost().replaceFirst("^www\\.", "") : "") + (uri.getPath() != null ? uri.getPath() : "");
        return label.length() > 36 ? label.substring(0, 35) + "…" : label;
    }

    private void setMode(int index) {
        // A queue draft left by a declined confirmation stays among the unscheduled posts, as on the dashboard.
        draftId = 0;
        if (index == MODE_SCHEDULE) {
            AlertsCreator.createScheduleDatePickerDialog(getContext(), dialogId, scheduleAt, 0, (notify, date, repeat) -> {
                scheduleAt = date;
                mode = MODE_SCHEDULE;
                tabs.selectTab(MODE_SCHEDULE, true);
                updateButton();
            }, () -> tabs.selectTab(mode, true), resourcesProvider).show();
            return;
        }
        mode = index;
        tabs.selectTab(index, true);
        updateButton();
    }

    private void buildProducts() {
        productList.removeAllViews();
        Context context = getContext();
        for (JSONObject offer : new ArrayList<>(picks)) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(6), dp(8));
            BackupImageView image = new BackupImageView(context);
            image.setRoundRadius(dp(8));
            image.setBackground(Theme.createRoundRectDrawable(dp(8), getThemedColor(Theme.key_windowBackgroundGray)));
            String photo = offer.optString("image", "");
            if (photo.startsWith("http")) {
                image.setImage(photo, "80_80", null);
            }
            row.addView(image, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));
            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(context);
            name.setText(Html.fromHtml(offer.optString("title", ""), Html.FROM_HTML_MODE_LEGACY).toString());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            info.addView(name);
            TextView price = new TextView(context);
            double deal = offer.optDouble("deal_price", Double.NaN);
            double discount = offer.optDouble("discount_percentage", Double.NaN);
            price.setText(AutoDealsPriceHistorySheet.price(deal, "EUR")
                + (!Double.isNaN(discount) && discount >= 1 ? " · -" + Math.round(discount) + "%" : ""));
            price.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            price.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            info.addView(price);
            row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            ImageView remove = new ImageView(context);
            remove.setImageResource(R.drawable.msg_close);
            remove.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon));
            remove.setScaleType(ImageView.ScaleType.CENTER);
            remove.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), .08f)));
            remove.setOnClickListener(v -> {
                picks.remove(offer);
                onPicksChanged.run();
                buildProducts();
                updateButton();
            });
            row.addView(remove, LayoutHelper.createLinear(40, 40));
            productList.addView(row);
        }
        if (picks.size() < MIN) {
            TextView hint = new TextView(context);
            hint.setText(picks.isEmpty()
                ? DealsGramText.get("MultiHintEmpty")
                : DealsGramText.get("MultiHintOne"));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            productList.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, picks.isEmpty() ? 8 : 4, 16, 8));
        }
    }

    private void updateButton() {
        String text;
        if (mode == MODE_SCHEDULE) {
            text = DealsGramText.format("ScheduleFor", new SimpleDateFormat(DealsGramText.get("DateAtTime"), DealsGramText.locale()).format(new Date(scheduleAt * 1000L)));
        } else if (mode == MODE_QUEUE) {
            text = DealsGramText.get("AddToQueue");
        } else {
            text = picks.size() >= MIN ? DealsGramText.format("PublishCount", picks.size()) : DealsGramText.get("Publish");
        }
        button.setText(text, true);
        button.setEnabled(!busy && picks.size() >= MIN);
    }

    /** "now", the ISO time chosen, or a draft for the queue: the queue decides when it goes out. */
    private String schedule() {
        if (mode == MODE_SCHEDULE) {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(scheduleAt * 1000L));
        }
        return mode == MODE_QUEUE ? "draft" : "now";
    }

    private void create(boolean confirmed) {
        if (busy || picks.size() < MIN) {
            return;
        }
        busy = true;
        button.setLoading(true);
        updateButton();
        int currentMode = mode;
        String schedule = schedule();
        String title = titleCell.getText() != null ? titleCell.getText().toString().trim() : "";
        List<JSONObject> chosen = new ArrayList<>(picks);
        Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            boolean askConfirm = false;
            try {
                AutoDealsApi api = AutoDealsApi.getInstance(account);
                if (draftId == 0) {
                    JSONObject params = new JSONObject();
                    params.put("channel_id", channelId);
                    JSONArray offers = new JSONArray();
                    for (JSONObject offer : chosen) {
                        JSONObject entry = new JSONObject();
                        entry.put("offer_id", offer.optLong("offer_id"));
                        putIfPresent(entry, "asin", offer.opt("asin"));
                        putIfPresent(entry, "store", offer.opt("store"));
                        putIfPresent(entry, "nation", offer.opt("nation"));
                        putIfPresent(entry, "title", offer.opt("title"));
                        putIfPresent(entry, "deal_price", offer.opt("deal_price"));
                        putIfPresent(entry, "original_price", offer.opt("original_price"));
                        putIfPresent(entry, "image", offer.opt("image"));
                        offers.put(entry);
                    }
                    params.put("offers", offers);
                    putIfPresent(params, "store", chosen.get(0).opt("store"));
                    putIfPresent(params, "nation", chosen.get(0).opt("nation"));
                    params.put("title", title);
                    params.put("schedule", schedule);
                    // Without it these shared actions skip the duplicate check (kept so for other clients).
                    params.put("check_product_duplicates", true);
                    if (confirmed) {
                        params.put("confirm_duplicates", true);
                    }
                    Object post = api.call("create_multi_post", params, origin);
                    if (currentMode == MODE_QUEUE && post instanceof JSONObject) {
                        draftId = ((JSONObject) post).optLong("id");
                    }
                }
                if (currentMode == MODE_QUEUE) {
                    JSONObject params = new JSONObject();
                    params.put("channel_id", channelId);
                    params.put("post_id", draftId);
                    params.put("post_type", "multi");
                    params.put("check_product_duplicates", true);
                    if (confirmed) {
                        params.put("confirm_duplicates", true);
                    }
                    api.call("add_post_to_curated", params, origin);
                }
            } catch (AutoDealsApi.AutoDealsException e) {
                if ("duplicate_product_confirmation_required".equals(e.code) && !confirmed) {
                    askConfirm = true;
                } else if ("not_allowed".equals(e.code)) {
                    error = DealsGramText.get("PlanNoMulti");
                } else {
                    error = e.text;
                }
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
                error = DealsGramText.get("MultiNotCreated");
            }
            boolean ask = askConfirm;
            String errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                button.setLoading(false);
                updateButton();
                if (ask) {
                    new AlertDialog.Builder(getContext(), resourcesProvider)
                        .setTitle(DealsGramText.get("RecentlyPublishedMany"))
                        .setMessage(DealsGramText.get("MultiDuplicate"))
                        .setPositiveButton(DealsGramText.get("Continue"), (d, w) -> create(true))
                        .setNegativeButton(DealsGramText.get("Cancel"), null)
                        .show();
                    return;
                }
                if (errorText != null) {
                    BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(errorText).show();
                    return;
                }
                String done;
                if (currentMode == MODE_QUEUE) {
                    done = DealsGramText.get("MultiQueued");
                } else if (currentMode == MODE_SCHEDULE) {
                    done = DealsGramText.format("MultiScheduledFor", new SimpleDateFormat(DealsGramText.get("DateAtTime"), DealsGramText.locale()).format(new Date(scheduleAt * 1000L)));
                } else {
                    done = DealsGramText.get("MultiCreated");
                }
                dismiss();
                onDone.run(done);
            });
        });
    }

    private static void putIfPresent(JSONObject target, String key, Object value) throws Exception {
        if (value != null && value != JSONObject.NULL) {
            target.put(key, value);
        }
    }
}
