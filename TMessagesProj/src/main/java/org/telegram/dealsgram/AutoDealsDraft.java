package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Modifica nel campo di scrittura": the post AutoDeals prepares for a link goes into Telegram's message field
 * instead of the publishing sheet. The text is free; the values that come from the format's tags stay live tags
 * (highlighted {@link LiveTag}), so AutoDeals fills them with the values of the moment when the post goes out.
 * Send publishes through AutoDeals with the edited text as a one-off format (custom_format), never from the
 * user's account.
 */
public class AutoDealsDraft {

    private static final int NOTE_MAX = 200;

    private static final String PREF_LINK_MODE = "link_mode";
    private static final String PREF_TAGS_HINT = "draft_tags_hint";
    /** An empty marker (a format line that is hidden today) is kept in the field as one invisible character. */
    private static final char HIDDEN = '\u2060';
    /** Stand-ins for the live tags while the field is turned into HTML: one private-use character each. */
    private static final char FIRST_TAG_CHAR = '\uE000';

    /** Drafts of chats that were closed, so they come back when the chat is opened again (memory only). */
    private static final Map<String, AutoDealsDraft> stashed = new HashMap<>();

    /**
     * One channel the post can go to. Only the channels opened in the field have a preview and a text of their own;
     * the others are published with their channel's format.
     */
    private static final class Target {
        final JSONObject channel;
        boolean selected;
        boolean loaded;
        JSONObject product;
        JSONObject customData;
        String mediaType = "";
        boolean imageFormat;
        boolean queue;
        Bitmap image;
        /** The field as it was left for this channel. */
        CharSequence text;

        Target(JSONObject channel) {
            this.channel = channel;
        }

        long id() {
            return channel.optLong("id");
        }

        String title() {
            return AutoDealsPublisher.channelTitle(channel);
        }
    }

    private ChatActivity fragment;
    private final int account;
    private final long dialogId;
    private final String link;
    private static final AutoDealsApi.Origin ORIGIN = new AutoDealsApi.Origin("field", "send_link");
    private final List<Target> targets = new ArrayList<>();
    /** The channel of the chat: selected from the start, as only one. */
    private final Target home;
    /** The channel whose post is in the field. */
    private Target inField;

    private boolean loading = true;
    private boolean sending;
    private boolean channelsLoaded;
    /** Nota per tutti i canali (top_note): plain text above every post. */
    private String note = "";
    private TextWatcher watcher;

    private AutoDealsDraft(ChatActivity fragment, long dialogId, JSONObject channel, String link) {
        this.fragment = fragment;
        this.account = UserConfig.selectedAccount;
        this.dialogId = dialogId;
        this.link = link;
        home = new Target(channel);
        home.selected = true;
        targets.add(home);
        inField = home;
    }

    // --- Setting -------------------------------------------------------------

    /**
     * Impostazioni → DealsGram: what a link sent in a linked channel does. The post in the field is part of
     * the subscription, so without a subscribed channel a link always opens the sheet.
     */
    public static boolean fieldMode() {
        return "field".equals(AutoDealsPublisher.prefs().getString(PREF_LINK_MODE, "sheet"))
            && AutoDealsPublisher.anySubscription(UserConfig.selectedAccount);
    }

    public static void setFieldMode(boolean field) {
        AutoDealsPublisher.prefs().edit().putString(PREF_LINK_MODE, field ? "field" : "sheet").apply();
    }

    // --- Start ---------------------------------------------------------------

    /** A link sent in a linked channel: prepares the post and puts it into the field. */
    static void start(ChatActivity fragment, long dialogId, JSONObject channel, String link) {
        if (fragment.autoDealsDraft != null) {
            return;
        }
        AutoDealsDraft draft = new AutoDealsDraft(fragment, dialogId, channel, link);
        fragment.autoDealsDraft = draft;
        draft.load(draft.home, null);
    }

    /** The preview of a channel's post, then its text in the field; previous is the channel to go back to on failure. */
    private void load(Target target, Target previous) {
        loading = true;
        fragment.showFieldPanelForAutoDeals();
        long channelId = target.id();
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject p;
            JSONObject c;
            JSONObject rendered;
            Bitmap bitmap;
            // Prepared while the link was in the field: no second request.
            AutoDealsPublisher.Prepared ready = AutoDealsPublisher.takePrepared(account, channelId, link, true);
            if (ready != null) {
                p = ready.product;
                c = ready.customData;
                rendered = ready.rendered;
                bitmap = ready.bitmap;
            } else {
                try {
                    AutoDealsApi api = AutoDealsApi.getInstance(account);
                    p = AutoDealsPublisher.scrape(api, channelId, link, ORIGIN);
                    c = AutoDealsPublisher.customData(p);
                    JSONObject params = new JSONObject();
                    params.put("channel_id", channelId);
                    params.put("offer_id", p.opt("offer_id"));
                    params.put("custom_data", c);
                    params.put("editable", true);
                    Object data = api.call("preview_post_message", params, ORIGIN);
                    rendered = data instanceof JSONObject ? (JSONObject) data : new JSONObject();
                } catch (AutoDealsApi.AutoDealsException e) {
                    AndroidUtilities.runOnUIThread(() -> fail(target, previous, e.text));
                    return;
                } catch (Exception e) {
                    AndroidUtilities.runOnUIThread(() -> fail(target, previous, DealsGramText.get("PreviewFailed")));
                    return;
                }
                String type = rendered.optString("media_type");
                byte[] bytes = "media".equals(type) || "attached".equals(type)
                    ? AutoDealsApi.download(rendered.optString("product_image", null)) : null;
                bitmap = bytes != null ? BitmapFactory.decodeByteArray(bytes, 0, bytes.length) : null;
            }
            AndroidUtilities.runOnUIThread(() -> loaded(target, previous, p, c, rendered, bitmap));
        });
    }

    private void loaded(Target target, Target previous, JSONObject p, JSONObject c, JSONObject rendered, Bitmap bitmap) {
        if (fragment.autoDealsDraft != this || inField != target || fragment.getParentActivity() == null) {
            return;
        }
        JSONObject editable = rendered.optJSONObject("editable");
        if (editable == null) {
            if (previous != null) {
                back(previous);
                BulletinFactory.of(fragment).createErrorBulletin(DealsGramText.get("PreviewFailed")).show();
                return;
            }
            // AutoDeals does not know the edited text yet: the sheet, never a post without the user's changes.
            close();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, DealsGramText.get("DraftServerOld")).show();
            new AutoDealsSheet(fragment, dialogId, link, link, () -> fragment.getChatActivityEnterView().setFieldText("")).from("send_link").show();
            return;
        }
        loading = false;
        target.loaded = true;
        target.product = p;
        target.customData = c;
        target.mediaType = rendered.optString("media_type");
        target.imageFormat = !rendered.isNull("image_format_id") && rendered.optLong("image_format_id") > 0;
        target.queue = editable.optBoolean("queue");
        target.image = bitmap;
        setField(toField(editable.optString("html"), fragment.getChatActivityEnterView().getEditField()));
        fragment.showFieldPanelForAutoDeals();
        String warning = p.optString("warning_message", "");
        if (!TextUtils.isEmpty(warning) && !"null".equals(warning)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, warning).show();
        } else if (previous == null && !AutoDealsPublisher.prefs().getBoolean(PREF_TAGS_HINT, false)) {
            AutoDealsPublisher.prefs().edit().putBoolean(PREF_TAGS_HINT, true).apply();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, DealsGramText.get("DraftLiveTagsHint")).show();
        }
    }

    private void fail(Target target, Target previous, String text) {
        if (fragment.autoDealsDraft != this || inField != target) {
            return;
        }
        if (previous != null) {
            back(previous);
        } else {
            // The link stays in the field: it can be sent again.
            close();
        }
        BulletinFactory.of(fragment).createErrorBulletin(text).show();
    }

    /** Back to the channel that was in the field before a failed switch. */
    private void back(Target previous) {
        loading = false;
        inField = previous;
        setField(previous.text);
        fragment.showFieldPanelForAutoDeals();
    }

    // --- Panel above the field ---------------------------------------------

    public Bitmap image() {
        return loading ? null : inField.image;
    }

    public CharSequence panelTitle() {
        int count = selectedCount();
        String title = inField != home ? inField.title()
            : count > 1 ? DealsGramText.format("DraftPanelTitleChannels", count) : DealsGramText.get("DraftPanelTitle");
        // The arrow says the panel opens the post's options.
        SpannableStringBuilder s = new SpannableStringBuilder(title).append(" v");
        ColoredImageSpan arrow = new ColoredImageSpan(R.drawable.arrow_more);
        arrow.setScale(.6f, .6f);
        s.setSpan(arrow, s.length() - 1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    public CharSequence panelSubtitle() {
        if (loading) {
            return DealsGramText.get("DraftPreparing");
        }
        List<String> parts = new ArrayList<>();
        if (inField != home) {
            parts.add(DealsGramText.format("DraftStyleOf", inField.title()));
        }
        if (!note.isEmpty()) {
            parts.add(DealsGramText.format("DraftNoteValue", note));
        } else if (inField == home) {
            String media = "media".equals(inField.mediaType) && inField.image != null ? DealsGramText.get("DraftMediaPhoto")
                : "attached".equals(inField.mediaType) && inField.image != null ? DealsGramText.get("DraftMediaPreview")
                : DealsGramText.get("DraftMediaNone");
            parts.add(inField.imageFormat && inField.image != null ? media + " · " + DealsGramText.get("DraftImageFormat") : media);
        }
        return TextUtils.join(" · ", parts);
    }

    /** Metti in coda needs every edited text to be accepted by the queue; unedited channels go as today. */
    public boolean canQueue() {
        if (loading) {
            return false;
        }
        for (Target target : targets) {
            if (target.selected && target.loaded && !target.queue) {
                return false;
            }
        }
        return true;
    }

    private int selectedCount() {
        int count = 0;
        for (Target target : targets) {
            if (target.selected) {
                count++;
            }
        }
        return count;
    }

    /** A tap on the panel: channels, note for all, schedule and queue. */
    public void showMenu(View anchor) {
        if (loading || sending) {
            return;
        }
        Paint.FontMetricsInt metrics = Theme.chat_msgTextPaint.getFontMetricsInt();
        ItemOptions options = ItemOptions.makeOptions(fragment, anchor).setGravity(Gravity.LEFT);
        options.add(R.drawable.msg_channel, DealsGramText.get("DraftChannels"), channelsSummary(), this::openChannels);
        options.add(R.drawable.msg_text_outlined, DealsGramText.get("DraftNote"),
            note.isEmpty() ? DealsGramText.get("DraftNoteNone") : Emoji.replaceEmoji(note, metrics, false), this::editNote);
        options.addGap();
        options.add(R.drawable.msg_calendar2, DealsGramText.get("Schedule"), this::schedule);
        if (canQueue()) {
            options.add(R.drawable.msg_list, DealsGramText.get("AddToQueue"), () -> send(0, true));
        }
        options.show();
    }

    private String channelsSummary() {
        List<String> names = new ArrayList<>();
        for (Target target : targets) {
            if (target.selected) {
                names.add(target.title());
            }
        }
        return names.size() <= 1 ? (names.isEmpty() ? "" : names.get(0)) : DealsGramText.format("DraftChannelsSummary", names.get(0), names.size() - 1);
    }

    private void schedule() {
        AlertsCreator.createScheduleDatePickerDialog(fragment.getParentActivity(), dialogId, new AlertsCreator.ScheduleDatePickerDelegate() {
            @Override
            public void didSelectDate(boolean notify, int scheduleDate, int scheduleRepeatPeriod) {
                send(scheduleDate, false);
            }
        }, fragment.getResourceProvider());
    }

    // --- Channels --------------------------------------------------------------

    private void openChannels() {
        if (channelsLoaded) {
            showChannels();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            List<JSONObject> list;
            try {
                list = AutoDealsPublisher.loadChannels(account);
            } catch (AutoDealsApi.AutoDealsException e) {
                AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createErrorBulletin(e.text).show());
                return;
            }
            // The photos: chats not in memory yet are read from Telegram's local database, as the sheet does.
            List<TLRPC.Chat> stored = new ArrayList<>();
            for (JSONObject channel : list) {
                long chatId = -(channel.optLong("id") + 1000000000000L);
                if (MessagesController.getInstance(account).getChat(chatId) == null) {
                    TLRPC.Chat chat = MessagesStorage.getInstance(account).getChatSync(chatId);
                    if (chat != null) {
                        stored.add(chat);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                for (TLRPC.Chat chat : stored) {
                    MessagesController.getInstance(account).putChat(chat, true);
                }
                for (JSONObject channel : list) {
                    boolean known = false;
                    for (Target target : targets) {
                        known |= target.id() == channel.optLong("id");
                    }
                    if (!known) {
                        targets.add(new Target(channel));
                    }
                }
                channelsLoaded = true;
                if (fragment.autoDealsDraft == this) {
                    showChannels();
                }
            });
        });
    }

    /** The channels to publish on: each goes out with its own format; Modifica puts its post in the field. */
    private void showChannels() {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(DealsGramText.get("DraftChannels"));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 0));
        TextView hint = new TextView(context);
        hint.setText(DealsGramText.get("DraftChannelsHint"));
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setLineSpacing(dp(2), 1f);
        content.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 8));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Target target : targets) {
            list.addView(channelRow(context, resourcesProvider, sheet, target), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        ButtonWithCounterView done = new ButtonWithCounterView(context, resourcesProvider).setRound();
        done.setText(DealsGramText.get("Done"), false);
        done.setOnClickListener(v -> sheet.dismiss());
        content.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 12));

        sheet.setCustomView(content);
        sheet.setOnHideListener(d -> {
            if (fragment.autoDealsDraft == this) {
                fragment.showFieldPanelForAutoDeals();
            }
        });
        sheet.show();
    }

    private LinearLayout channelRow(Context context, Theme.ResourcesProvider resourcesProvider, BottomSheet sheet, Target target) {
        // A Free channel publishes only as its format makes it: no edited text or note, so not from here.
        boolean subscribed = AutoDealsPublisher.hasSubscription(target.channel);
        boolean usable = subscribed && AutoDealsPublisher.canPublish(target.channel);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(16), 0);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.addView(AutoDealsPublisher.channelAvatar(context, account, target.channel, 21), LayoutHelper.createLinear(42, 42, 0, 0, 14, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(target.title());
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView state = new TextView(context);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(state, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        CheckBox2 check = new CheckBox2(context, 22, resourcesProvider);
        // A grey ring when off, Telegram's blue check when on.
        check.setColor(Theme.key_checkbox, Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_checkboxCheck);
        check.setDrawBackgroundAsArc(10);
        check.setDrawUnchecked(true);
        check.setChecked(target.selected, false);
        Runnable updateState = () -> {
            boolean edited = target == inField || target.loaded && target.text != null;
            state.setText(!usable ? AutoDealsPlanSheet.planLine(target.channel)
                : target == inField ? DealsGramText.get("DraftStateInField")
                : !target.selected ? DealsGramText.get("DraftStateOff")
                : edited ? DealsGramText.get("DraftStateEdited")
                : DealsGramText.get("DraftStateFormat"));
            state.setTextColor(Theme.getColor(usable && target.selected && edited ? Theme.key_featuredStickers_addButton : Theme.key_dialogTextGray3, resourcesProvider));
        };
        updateState.run();

        if (usable && target != inField) {
            TextView edit = new TextView(context);
            edit.setText(DealsGramText.get("DraftEditChannel"));
            edit.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
            edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            edit.setTypeface(AndroidUtilities.bold());
            edit.setGravity(Gravity.CENTER);
            edit.setPadding(dp(12), 0, dp(12), 0);
            edit.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(15),
                Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), 0.1f),
                Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), 0.2f)));
            edit.setOnClickListener(v -> {
                sheet.dismiss();
                edit(target);
            });
            row.addView(edit, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        }
        row.addView(check, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        if (!usable) {
            row.setAlpha(0.6f);
        }
        row.setOnClickListener(v -> {
            // The channel in the field always goes out: to drop the post there is the X.
            if (!subscribed) {
                AutoDealsPlanSheet.showFeature(context, resourcesProvider, AutoDealsPlanSheet.FEATURE_FIELD, target.id());
                return;
            }
            if (!usable || target == inField) {
                return;
            }
            target.selected = !target.selected;
            check.setChecked(target.selected, true);
            updateState.run();
        });
        return row;
    }

    /** Modifica: the channel's post, in its own style, takes the field; the current text is kept for its channel. */
    private void edit(Target target) {
        if (loading || sending || target == inField) {
            return;
        }
        target.selected = true;
        Target previous = inField;
        previous.text = new SpannableStringBuilder(fragment.getChatActivityEnterView().getEditField().getText());
        inField = target;
        if (target.loaded) {
            setField(target.text);
            fragment.showFieldPanelForAutoDeals();
        } else {
            setField("");
            load(target, previous);
        }
    }

    // --- Note for all channels ---------------------------------------------------

    private void editNote() {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(context);
        info.setText(DealsGramText.get("DraftNoteInfo"));
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 10));
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setText(note);
        input.setHint(DealsGramText.get("DraftNoteHint"));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField, resourcesProvider), Theme.getColor(Theme.key_dialogInputFieldActivated, resourcesProvider), Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        input.setBackground(null);
        input.setPadding(0, dp(6), 0, dp(6));
        input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(NOTE_MAX)});
        box.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        TextView counter = new TextView(context);
        counter.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        counter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        counter.setGravity(Gravity.RIGHT);
        counter.setText(note.length() + "/" + NOTE_MAX);
        box.addView(counter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 0));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                counter.setText(s.length() + "/" + NOTE_MAX);
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
            .setTitle(DealsGramText.get("DraftNoteTitle"))
            .setView(box)
            .setPositiveButton(DealsGramText.get("Save"), (d, which) -> {
                note = input.getText() != null ? input.getText().toString().trim() : "";
                fragment.showFieldPanelForAutoDeals();
            })
            .setNegativeButton(DealsGramText.get("Cancel"), null)
            .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.setSelection(input.length());
            AndroidUtilities.showKeyboard(input);
        });
        dialog.show();
    }

    /** The X of the panel: the AutoDeals post is dropped, the field is emptied. */
    public void cancel() {
        if (sending) {
            return;
        }
        boolean hadText = !loading || inField != home;
        close();
        if (hadText) {
            fragment.getChatActivityEnterView().setFieldText("");
        }
    }

    private void close() {
        removeWatcher();
        stashed.remove(key(account, dialogId));
        if (fragment.autoDealsDraft == this) {
            fragment.autoDealsDraft = null;
        }
        fragment.fallbackFieldPanel();
    }

    // --- Leaving and coming back -------------------------------------------

    /**
     * Called by ChatActivity.saveDraft(): the post is kept here instead of Telegram's cloud draft, so it can
     * never come back as a plain message sent from the user's account. Returns true while there is a draft.
     */
    public boolean stash() {
        if (loading) {
            return true;
        }
        inField.text = new SpannableStringBuilder(fragment.getChatActivityEnterView().getEditField().getText());
        stashed.put(key(account, dialogId), this);
        return true;
    }

    /** Called by ChatActivity.onResume(): the post edited before the chat was closed, if any. */
    public static void restore(ChatActivity fragment) {
        if (fragment.autoDealsDraft != null || fragment.getChatActivityEnterView() == null) {
            return;
        }
        AutoDealsDraft draft = stashed.get(key(UserConfig.selectedAccount, fragment.getDialogId()));
        if (draft == null || draft.inField.text == null || !TextUtils.isEmpty(fragment.getChatActivityEnterView().getFieldText())) {
            return;
        }
        draft.fragment = fragment;
        fragment.autoDealsDraft = draft;
        draft.setField(draft.inField.text);
        fragment.showFieldPanelForAutoDeals();
    }

    private static String key(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    // --- The field and its live tags ---------------------------------------

    /** A value that comes from a tag of the format: highlighted, moved or deleted whole, filled again at publication. */
    public static class LiveTag extends BackgroundColorSpan {
        final String src;
        final String value;

        LiveTag(String src, String value, int color) {
            super(color);
            this.src = src;
            this.value = value;
        }
    }

    private void setField(CharSequence text) {
        removeWatcher();
        ChatActivityEnterView enterView = fragment.getChatActivityEnterView();
        enterView.setFieldText(text);
        EditTextCaption field = enterView.getEditField();
        watcher = new TextWatcher() {
            private boolean removing;
            private boolean changing;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                removing = count > 0 && after == 0;
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (changing) {
                    return;
                }
                changing = true;
                // A tag that lost characters goes away whole; a tag written inside becomes plain text.
                for (LiveTag tag : s.getSpans(0, s.length(), LiveTag.class)) {
                    int from = s.getSpanStart(tag);
                    int to = s.getSpanEnd(tag);
                    if (from < 0 || TextUtils.equals(s.subSequence(from, to), tag.value)) {
                        continue;
                    }
                    s.removeSpan(tag);
                    if (removing) {
                        s.delete(from, to);
                    }
                }
                changing = false;
            }
        };
        field.addTextChangedListener(watcher);
    }

    private void removeWatcher() {
        if (watcher != null && fragment.getChatActivityEnterView() != null && fragment.getChatActivityEnterView().getEditField() != null) {
            fragment.getChatActivityEnterView().getEditField().removeTextChangedListener(watcher);
        }
        watcher = null;
    }

    private static final Pattern HTML_TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /** Telegram HTML with ad-tag markers → the text for the field, with Telegram's own spans and the live tags. */
    private CharSequence toField(String html, EditTextCaption field) {
        StringBuilder text = new StringBuilder();
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        List<int[]> tagRanges = new ArrayList<>();
        List<String> tagSources = new ArrayList<>();
        List<Object[]> open = new ArrayList<>(); // {name, start, attributes}
        int pre = 0;
        Matcher m = HTML_TAG.matcher(html);
        int last = 0;
        while (m.find()) {
            text.append(decode(html.substring(last, m.start())));
            last = m.end();
            String name = m.group(2).toLowerCase(Locale.US);
            if (m.group(1).isEmpty()) {
                if ("br".equals(name)) {
                    text.append('\n');
                    continue;
                }
                if ("pre".equals(name)) {
                    pre++;
                }
                open.add(new Object[]{name, text.length(), attributes(m.group(3))});
                continue;
            }
            for (int i = open.size() - 1; i >= 0; i--) {
                Object[] o = open.get(i);
                if (!name.equals(o[0])) {
                    continue;
                }
                open.remove(i);
                int start = (Integer) o[1];
                @SuppressWarnings("unchecked") Map<String, String> attrs = (Map<String, String>) o[2];
                if ("ad-tag".equals(name)) {
                    if (text.length() == start) {
                        text.append(HIDDEN);
                    }
                    tagRanges.add(new int[]{start, text.length()});
                    tagSources.add(attrs.containsKey("src") ? attrs.get("src") : "");
                } else {
                    if ("pre".equals(name)) {
                        pre--;
                    }
                    TLRPC.MessageEntity entity = entity(name, attrs, pre > 0);
                    if (entity != null && text.length() > start) {
                        entity.offset = start;
                        entity.length = text.length() - start;
                        entities.add(entity);
                    }
                }
                break;
            }
        }
        text.append(decode(html.substring(last)));

        CharSequence styled = ChatActivityEnterView.applyMessageEntities(entities, text.toString(), field.getPaint().getFontMetricsInt());
        SpannableStringBuilder result = styled instanceof SpannableStringBuilder ? (SpannableStringBuilder) styled : new SpannableStringBuilder(styled);
        int color = Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, fragment.getResourceProvider()), 0.16f);
        for (int i = 0; i < tagRanges.size(); i++) {
            int[] r = tagRanges.get(i);
            String value = result.subSequence(r[0], r[1]).toString();
            result.setSpan(new LiveTag(tagSources.get(i), value, color), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return Emoji.replaceEmoji(result, field.getPaint().getFontMetricsInt(), false);
    }

    private static TLRPC.MessageEntity entity(String name, Map<String, String> attrs, boolean insidePre) {
        switch (name) {
            case "b":
            case "strong":
                return new TLRPC.TL_messageEntityBold();
            case "i":
            case "em":
                return new TLRPC.TL_messageEntityItalic();
            case "u":
            case "ins":
                return new TLRPC.TL_messageEntityUnderline();
            case "s":
            case "strike":
            case "del":
                return new TLRPC.TL_messageEntityStrike();
            case "tg-spoiler":
                return new TLRPC.TL_messageEntitySpoiler();
            case "span":
                return "tg-spoiler".equals(attrs.get("class")) ? new TLRPC.TL_messageEntitySpoiler() : null;
            case "code":
                return insidePre ? null : new TLRPC.TL_messageEntityCode();
            case "pre":
                return new TLRPC.TL_messageEntityCode();
            case "a": {
                String href = attrs.get("href");
                if (TextUtils.isEmpty(href)) {
                    return null;
                }
                TLRPC.TL_messageEntityTextUrl url = new TLRPC.TL_messageEntityTextUrl();
                url.url = href;
                return url;
            }
            case "tg-emoji": {
                TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                emoji.document_id = Utilities.parseLong(attrs.get("emoji-id"));
                return emoji.document_id != 0 ? emoji : null;
            }
            default:
                return null;
        }
    }

    private static Map<String, String> attributes(String source) {
        Map<String, String> attrs = new HashMap<>();
        Matcher m = ATTRIBUTE.matcher(source);
        while (m.find()) {
            attrs.put(m.group(1).toLowerCase(Locale.US), decode(m.group(2) != null ? m.group(2) : m.group(3)));
        }
        return attrs;
    }

    private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");

    private static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        Matcher m = CHARACTER_REFERENCE.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            last = m.end();
            String ref = m.group(1);
            try {
                if (ref.startsWith("#x") || ref.startsWith("#X")) {
                    out.appendCodePoint(Integer.parseInt(ref.substring(2), 16));
                } else if (ref.startsWith("#")) {
                    out.appendCodePoint(Integer.parseInt(ref.substring(1)));
                } else {
                    switch (ref) {
                        case "amp": out.append('&'); break;
                        case "lt": out.append('<'); break;
                        case "gt": out.append('>'); break;
                        case "quot": out.append('"'); break;
                        case "apos": out.append('\''); break;
                        case "nbsp": out.append('\u00A0'); break;
                        default: out.append(m.group());
                    }
                }
            } catch (IllegalArgumentException e) {
                out.append(m.group());
            }
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    /**
     * The field → custom_format: Telegram HTML of the user's text, each live tag replaced by its source ({price}, or
     * the whole fragment of a hidden line) and braces typed by the user escaped, so AutoDeals never reads them as tags.
     */
    String buildFormat(CharSequence field) {
        Spanned source = field instanceof Spanned ? (Spanned) field : new SpannableStringBuilder(field);
        LiveTag[] tags = source.getSpans(0, source.length(), LiveTag.class);
        Arrays.sort(tags, (a, b) -> source.getSpanStart(a) - source.getSpanStart(b));
        // Each tag becomes one private-use character; spans keep their place around it.
        StringBuilder text = new StringBuilder();
        int[] map = new int[source.length() + 1];
        List<String> sources = new ArrayList<>();
        int pos = 0;
        for (LiveTag tag : tags) {
            int from = source.getSpanStart(tag);
            int to = source.getSpanEnd(tag);
            if (from < pos) {
                continue;
            }
            for (; pos < from; pos++) {
                map[pos] = text.length();
                text.append(source.charAt(pos));
            }
            for (; pos < to; pos++) {
                map[pos] = text.length();
            }
            text.append((char) (FIRST_TAG_CHAR + sources.size()));
            sources.add(tag.src);
        }
        for (; pos < source.length(); pos++) {
            map[pos] = text.length();
            text.append(source.charAt(pos));
        }
        map[source.length()] = text.length();
        SpannableStringBuilder flat = new SpannableStringBuilder(text);
        for (Object span : source.getSpans(0, source.length(), Object.class)) {
            if (span instanceof LiveTag) {
                continue;
            }
            int from = source.getSpanStart(span);
            int to = source.getSpanEnd(span);
            boolean insideTag = false;
            for (LiveTag tag : tags) {
                int tf = source.getSpanStart(tag);
                int tt = source.getSpanEnd(tag);
                if (from >= tf && to <= tt && (from > tf || to < tt)) {
                    insideTag = true; // formatting of the value: the tag brings it back
                    break;
                }
            }
            int start = from >= 0 && from < map.length ? map[from] : -1;
            int end = to >= 0 && to < map.length ? map[to] : -1;
            if (!insideTag && start >= 0 && end > start && end <= flat.length()) {
                flat.setSpan(span, start, end, source.getSpanFlags(span));
            }
        }
        CharSequence[] message = new CharSequence[]{AndroidUtilities.getTrimmedString(flat)};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account).getEntities(message, true);
        return toHtml(message[0].toString(), entities, sources);
    }

    /**
     * The note above the post (top_note) as Telegram HTML, with only what the server accepts in a note: bold, italic,
     * underline, strike, spoiler, inline code (never inside other formatting) and https links. Null when the note has
     * no formatting left, so it is sent as plain text exactly as before.
     */
    static String noteHtml(int account, CharSequence note) {
        if (note == null) {
            return null;
        }
        CharSequence[] message = new CharSequence[]{AndroidUtilities.getTrimmedString(note)};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account).getEntities(message, true);
        ArrayList<TLRPC.MessageEntity> kept = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                boolean inline = e instanceof TLRPC.TL_messageEntityBold || e instanceof TLRPC.TL_messageEntityItalic
                    || e instanceof TLRPC.TL_messageEntityUnderline || e instanceof TLRPC.TL_messageEntityStrike
                    || e instanceof TLRPC.TL_messageEntitySpoiler || e instanceof TLRPC.TL_messageEntityCode;
                boolean link = e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null && e.url.regionMatches(true, 0, "https://", 0, 8);
                if ((inline || link) && e.length > 0) {
                    kept.add(e);
                }
            }
        }
        // Telegram does not combine inline code with other formatting: the code wins over what overlaps it.
        ArrayList<TLRPC.MessageEntity> usable = new ArrayList<>();
        for (TLRPC.MessageEntity e : kept) {
            boolean overlapsCode = false;
            if (!(e instanceof TLRPC.TL_messageEntityCode)) {
                for (TLRPC.MessageEntity c : kept) {
                    if (c instanceof TLRPC.TL_messageEntityCode && e.offset < c.offset + c.length && c.offset < e.offset + e.length) {
                        overlapsCode = true;
                    }
                }
            }
            if (!overlapsCode) {
                usable.add(e);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        return toHtml(message[0].toString(), usable, new ArrayList<>());
    }

    private static String toHtml(String text, ArrayList<TLRPC.MessageEntity> entities, List<String> sources) {
        List<TLRPC.MessageEntity> usable = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                if (openTag(e) != null && e.length > 0) {
                    usable.add(e);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        List<TLRPC.MessageEntity> stack = new ArrayList<>();
        for (int i = 0; i <= text.length(); i++) {
            // The entities covering this character, outermost first; tags are closed and reopened to stay nested.
            List<TLRPC.MessageEntity> active = new ArrayList<>();
            if (i < text.length()) {
                for (TLRPC.MessageEntity e : usable) {
                    if (e.offset <= i && i < e.offset + e.length) {
                        active.add(e);
                    }
                }
                active.sort((a, b) -> a.offset != b.offset ? a.offset - b.offset : (b.offset + b.length) - (a.offset + a.length));
            }
            int keep = 0;
            while (keep < stack.size() && keep < active.size() && stack.get(keep) == active.get(keep)) {
                keep++;
            }
            for (int k = stack.size() - 1; k >= keep; k--) {
                out.append(closeTag(stack.remove(k)));
            }
            for (int k = keep; k < active.size(); k++) {
                out.append(openTag(active.get(k)));
                stack.add(active.get(k));
            }
            if (i == text.length()) {
                break;
            }
            char c = text.charAt(i);
            int tag = c - FIRST_TAG_CHAR;
            if (tag >= 0 && tag < sources.size()) {
                out.append(sources.get(tag));
            } else if (c == '&') {
                out.append("&amp;");
            } else if (c == '<') {
                out.append("&lt;");
            } else if (c == '>') {
                out.append("&gt;");
            } else if (c == '{') {
                out.append("&#123;");
            } else if (c == '}') {
                out.append("&#125;");
            } else if (c != HIDDEN) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String openTag(TLRPC.MessageEntity e) {
        if (e instanceof TLRPC.TL_messageEntityBold) return "<b>";
        if (e instanceof TLRPC.TL_messageEntityItalic) return "<i>";
        if (e instanceof TLRPC.TL_messageEntityUnderline) return "<u>";
        if (e instanceof TLRPC.TL_messageEntityStrike) return "<s>";
        if (e instanceof TLRPC.TL_messageEntitySpoiler) return "<tg-spoiler>";
        if (e instanceof TLRPC.TL_messageEntityCode) return "<code>";
        if (e instanceof TLRPC.TL_messageEntityPre) return "<pre>";
        if (e instanceof TLRPC.TL_messageEntityBlockquote) return ((TLRPC.TL_messageEntityBlockquote) e).collapsed ? "<blockquote expandable>" : "<blockquote>";
        if (e instanceof TLRPC.TL_messageEntityTextUrl) return "<a href=\"" + escapeAttribute(e.url) + "\">";
        if (e instanceof TLRPC.TL_inputMessageEntityMentionName) return "<a href=\"tg://user?id=" + ((TLRPC.TL_inputMessageEntityMentionName) e).user_id.user_id + "\">";
        if (e instanceof TLRPC.TL_messageEntityCustomEmoji) return "<tg-emoji emoji-id=\"" + ((TLRPC.TL_messageEntityCustomEmoji) e).document_id + "\">";
        return null;
    }

    private static String closeTag(TLRPC.MessageEntity e) {
        String open = openTag(e);
        int end = open.indexOf(' ');
        return "</" + open.substring(1, end > 0 ? end : open.length() - 1) + ">";
    }

    private static String escapeAttribute(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // --- Send ----------------------------------------------------------------

    /**
     * Invia (scheduleDate 0), "Programma messaggio" (a date) or "Metti in coda": AutoDeals publishes the post on every
     * selected channel, with the text edited for it (custom_format) or with its own format, and the note for all.
     */
    public void send(int scheduleDate, boolean toQueue) {
        if (toQueue && !note.isEmpty()) {
            // The queue builds its posts later, without a note: say so before queueing.
            new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(DealsGramText.get("AddToQueue"))
                .setMessage(DealsGramText.get("DraftQueueNoNote"))
                .setPositiveButton(DealsGramText.get("AddToQueue"), (d, which) -> send(0, true, false))
                .setNegativeButton(DealsGramText.get("Cancel"), null)
                .show();
            return;
        }
        send(scheduleDate, toQueue, false);
    }

    private void send(int scheduleDate, boolean toQueue, boolean confirmDuplicates) {
        if (loading || sending) {
            return;
        }
        CharSequence field = fragment.getChatActivityEnterView().getEditField().getText();
        if (inField.selected && TextUtils.isEmpty(AndroidUtilities.getTrimmedString(field.toString().replace(String.valueOf(HIDDEN), "")))) {
            BulletinFactory.of(fragment).createErrorBulletin(DealsGramText.get("DraftEmpty")).show();
            return;
        }
        inField.text = new SpannableStringBuilder(field);
        // The channel in the field first, then the others in their order. A channel already published by a
        // previous attempt is no longer selected, so it never gets the post twice.
        List<Target> chosen = new ArrayList<>();
        if (inField.selected) {
            chosen.add(inField);
        }
        for (Target target : targets) {
            if (target.selected && target != inField) {
                chosen.add(target);
            }
        }
        Map<Target, String> formats = new HashMap<>();
        for (Target target : chosen) {
            if (target.loaded && target.text != null) {
                formats.put(target, buildFormat(target.text));
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        String schedule = scheduleDate > 0 ? new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(scheduleDate * 1000L)) : "now";
        String topNote = note;
        sending = true;
        Utilities.globalQueue.postRunnable(() -> {
            AutoDealsApi api = AutoDealsApi.getInstance(account);
            List<String> failures = new ArrayList<>();
            List<Target> succeeded = new ArrayList<>();
            List<Target> duplicates = new ArrayList<>();
            JSONObject limitReached = null;
            int position = 0;
            for (Target target : chosen) {
                String format = formats.get(target);
                try {
                    JSONObject p = target.product;
                    JSONObject c = target.customData;
                    if (!target.loaded) {
                        // Affiliate tag, store and format are the channel's own: read the link for it.
                        p = AutoDealsPublisher.scrape(api, target.id(), link, ORIGIN);
                        c = AutoDealsPublisher.customData(p);
                    }
                    if (toQueue) {
                        long offerId = p.optLong("offer_id");
                        if (offerId <= 0) {
                            failures.add(DealsGramText.format("ChannelCannotQueue", target.title()));
                            continue;
                        }
                        JSONObject params = new JSONObject();
                        params.put("channel_id", target.id());
                        params.put("offer_id", offerId);
                        params.put("offer_action", "add_curated");
                        if (format != null) {
                            params.put("custom_format", format);
                        }
                        if (confirmDuplicates) {
                            params.put("confirm_duplicates", true);
                        }
                        Object data = api.call("autoposting_v2_offer_action", params, ORIGIN);
                        if (data instanceof JSONObject && ((JSONObject) data).optJSONArray("items") != null) {
                            position = ((JSONObject) data).optJSONArray("items").length();
                        }
                    } else {
                        JSONObject params = AutoDealsPublisher.createParams(target.channel, p, c, schedule, topNote);
                        if (format != null) {
                            params.put("custom_format", format);
                        }
                        api.call("create_post", params, ORIGIN);
                    }
                    succeeded.add(target);
                } catch (AutoDealsApi.AutoDealsException e) {
                    if (toQueue && "duplicate_product_confirmation_required".equals(e.code)) {
                        duplicates.add(target);
                    } else {
                        failures.add(target.title() + ": " + e.text);
                        if ("free_plan_daily_limit".equals(e.code)) {
                            JSONObject free = AutoDealsPublisher.freePlan(target.channel);
                            limitReached = free != null ? free : new JSONObject();
                        }
                    }
                } catch (Exception e) {
                    failures.add(DealsGramText.format(toQueue ? "ChannelNotQueued" : "ChannelPublishFailed", target.title()));
                }
            }
            JSONObject limit = limitReached;
            int queued = position;
            AndroidUtilities.runOnUIThread(() -> sent(chosen, succeeded, failures, duplicates, limit, scheduleDate, toQueue, queued));
        });
    }

    private void sent(List<Target> chosen, List<Target> succeeded, List<String> failures, List<Target> duplicates,
                      JSONObject limit, int scheduleDate, boolean toQueue, int queued) {
        sending = false;
        if (fragment.getParentActivity() == null) {
            return;
        }
        // Channels already done leave the selection, so trying again cannot post twice on them.
        for (Target target : succeeded) {
            target.selected = false;
        }
        boolean allDone = succeeded.size() == chosen.size();
        if (allDone) {
            close();
            fragment.getChatActivityEnterView().setFieldText("");
            int done = succeeded.size();
            String message = toQueue ? (done > 1 ? DealsGramText.format("QueuedChannels", done) : queued > 0 ? DealsGramText.format("QueuedPosition", queued) : DealsGramText.get("Queued"))
                // "now" schedules the post at the current minute: the AutoDeals worker publishes it.
                : scheduleDate > 0 ? (done > 1 ? DealsGramText.format("ScheduledForChannels", done, AutoDealsSheet.formatScheduleTime(scheduleDate)) : DealsGramText.format("ScheduledFor", AutoDealsSheet.formatScheduleTime(scheduleDate)))
                : done > 1 ? DealsGramText.format("PublishingSoonChannels", done) : DealsGramText.get("PublishingSoon");
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, message).show();
            return;
        }
        fragment.showFieldPanelForAutoDeals();
        if (limit != null && chosen.size() == 1) {
            AutoDealsPlanSheet.showLimit(fragment.getParentActivity(), fragment.getResourceProvider(), limit, chosen.get(0).id());
            return;
        }
        if (!duplicates.isEmpty() && failures.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Target target : duplicates) {
                names.add(target.title());
            }
            new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(DealsGramText.get("RecentlyPublished"))
                .setMessage(DealsGramText.format("DuplicateOnChannels", TextUtils.join(", ", names)))
                .setPositiveButton(DealsGramText.get("AddToQueue"), (dialog, which) -> send(0, true, true))
                .setNegativeButton(DealsGramText.get("Cancel"), null)
                .show();
            return;
        }
        for (Target target : duplicates) {
            failures.add(DealsGramText.format("ChannelRecentlyPublished", target.title()));
        }
        BulletinFactory.of(fragment).createErrorBulletin(TextUtils.join(" · ", failures)).show();
    }
}
