package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import androidx.core.widget.TextViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.AIEditorAlert;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingSpan;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The AutoDeals publishing sheet, built from the same pieces as Telegram's AI Editor:
 * grey sheet, white rounded cards, icon tabs, shimmer while loading, one large button.
 */
public class AutoDealsSheet extends BottomSheet {

    private ScrollView scroll;

    /** DealsGram: a swipe down closes the sheet only from the top of the list; elsewhere it scrolls the list. */
    @Override
    protected boolean canDismissWithSwipe() {
        return scroll == null || !scroll.canScrollVertically(-1);
    }

    private static final int MODE_NOW = 0;
    private static final int MODE_SCHEDULE = 1;
    /**
     * End of the Autoposting V2 curated queue, published at the next V2 hour; the tab exists only when
     * a channel has V2 on.
     */
    private static final int MODE_QUEUE = 2;
    private static final int TOP_NOTE_MAX = 200;
    private static final int PREVIEW_MAX_CHARS = 1500;

    private final BaseFragment fragment;
    private final int account;
    private final long dialogId;
    private final String originalText;
    private final Runnable clearInput;

    private String link;
    /** How the user opened the window, for the "client" block of every request. */
    private AutoDealsApi.Origin origin = new AutoDealsApi.Origin("sheet", "none");
    private List<JSONObject> channels = new ArrayList<>();
    /** Channels to publish on, in list order; the first one is the one previewed. */
    private final List<Integer> selected = new ArrayList<>();
    private int previewChannelIndex = -1;
    private int mode = MODE_NOW;
    /** Chosen publication time for MODE_SCHEDULE, unix seconds. */
    private int scheduleAt;
    private AIEditorAlert.Tabs modeTabs;
    private int requestToken;

    private JSONObject product;
    private JSONObject customData;
    private boolean previewReady;
    /** The server takes the note as Telegram HTML (preview_post_message lists "html" in top_note_formats). */
    private boolean noteHtml;
    private boolean publishing;

    /** Channels in a pill next to the title: one, "Name + 2" or "All · 5"; it opens the list with checkboxes. */
    private final LinearLayout channelPill;
    private final FrameLayout pillAvatars;
    private final TextView pillText;
    private final FrameLayout linkBox;
    private final EditTextCell linkCell;
    private final View previewCard;
    private final TextView previewChannel;
    private final ImageView photo;
    private final TextView previewText;
    /** The note above the post (top_note), written inside the preview bubble. */
    /** Telegram's caption field with its emoji panel (EditTextEmoji, as the share sheet's comment). */
    private final org.telegram.ui.Components.EditTextEmoji noteField;
    /** The channel's "Testi custom predefiniti" (Bot settings) as one-tap notes, right under the note. */
    private final HorizontalScrollView notePresetsScroll;
    private final LinearLayout notePresets;
    /** Preset texts per channel id, loaded once per sheet with get_settings. */
    private final java.util.Map<Long, List<String>> presetsByChannel = new java.util.HashMap<>();
    private static final org.telegram.messenger.DispatchQueue PRESETS_QUEUE = new org.telegram.messenger.DispatchQueue("autoDealsNotePresets");
    private final LinearLayout keyboardBox;
    /** Format with media_type "attached": the image is a link preview under the text, not a photo on top. */
    private final LinearLayout linkPreview;
    private final ImageView linkPreviewImage;
    private final TextView imageFormatNote;
    private final TextView checkText;
    private final ButtonWithCounterView button;
    private final FrameLayout bulletinContainer;
    private final LinearLayout freeBox;
    private final TextView freeText;
    private AIEditorAlert.Tabs.Tab scheduleTab;
    private AIEditorAlert.Tabs.Tab queueTab;
    /** The plan of a selected channel does not allow scheduling, or has no queue: the tab opens the plan sheet. */
    private boolean scheduleLocked;
    private boolean queueLocked;

    public AutoDealsSheet(BaseFragment fragment, long dialogId, String link, String originalText, Runnable clearInput) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.account = UserConfig.selectedAccount;
        this.dialogId = dialogId;
        this.link = link != null ? link.trim() : "";
        this.originalText = originalText;
        this.clearInput = clearInput;

        Context context = getContext();
        int gray = getThemedColor(Theme.key_windowBackgroundGray);
        setBackgroundColor(gray);
        fixNavigationBar(gray);
        setApplyBottomPadding(false);

        // Never taller than the screen below the status bar: the cards scroll inside. A SizeNotifierFrameLayout,
        // because the note's emoji panel is added to it and takes the keyboard's place at the bottom.
        org.telegram.ui.Components.SizeNotifierFrameLayout root = new org.telegram.ui.Components.SizeNotifierFrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int emoji = noteField != null && noteField.isPopupShowing() ? noteField.getEmojiPadding() : 0;
                setPadding(0, 0, 0, emoji);
                int max = AndroidUtilities.displaySize.y - AndroidUtilities.statusBarHeight - dp(56);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(heightMeasureSpec), max), MeasureSpec.AT_MOST));
                // The panel lives in the bottom padding, which FrameLayout would not give it: measure it at its own size.
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (noteField != null && noteField.isPopupView(child) && child.getVisibility() != GONE) {
                        child.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.max(emoji, noteField.getEmojiPadding()), MeasureSpec.EXACTLY));
                    }
                }
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (noteField != null && noteField.isPopupView(child)) {
                        int height = getMeasuredHeight();
                        child.layout(0, height - child.getMeasuredHeight(), child.getMeasuredWidth(), height);
                    }
                }
            }
        };
        // The panel is drawn inside the bottom padding: do not clip it away.
        root.setClipToPadding(false);
        scroll = new ScrollView(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(6 + 48 + 12 + 8 + (originalText != null ? 36 : 0)));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Header: title, channels and close, like the AI Editor.
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(DealsGramText.get("PublishDeal"));
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        // Long titles (German) shrink instead of pushing the pill out.
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, 14, 20, 1, TypedValue.COMPLEX_UNIT_DIP);
        header.addView(title, LayoutHelper.createLinear(0, 28, 1f, 22, 0, 8, 0));
        channelPill = new LinearLayout(context);
        channelPill.setGravity(Gravity.CENTER_VERTICAL);
        channelPill.setPadding(dp(4), dp(4), dp(10), dp(4));
        channelPill.setBackground(Theme.createRoundRectDrawable(dp(16), getThemedColor(Theme.key_windowBackgroundWhite)));
        pillAvatars = new FrameLayout(context);
        channelPill.addView(pillAvatars, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 24, 0, 0, 6, 0));
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
        channelPill.setOnClickListener(v -> pickChannels());
        channelPill.setVisibility(View.GONE);
        header.addView(channelPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), .10f)));
        ScaleStateListAnimator.apply(close, .1f, 1.5f);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createLinear(48, 48, 4, 0, 8, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));

        // What to do with the offer.
        modeTabs = new AIEditorAlert.Tabs(context, account, false, resourcesProvider);
        modeTabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        modeTabs.setBackground(Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite)));
        modeTabs.setRoundRadius(28);
        modeTabs.addTab(R.drawable.msg_send, DealsGramText.get("PublishNow"), this::setMode);
        scheduleTab = modeTabs.addTab(R.drawable.msg_calendar2, DealsGramText.get("Schedule"), this::setMode);
        modeTabs.selectTab(MODE_NOW, false);
        column.addView(modeTabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 12, 0, 12, 10));

        // Free plan: posts left today, with the way to a subscription.
        freeBox = new LinearLayout(context);
        freeBox.setGravity(Gravity.CENTER_VERTICAL);
        freeBox.setPadding(dp(16), dp(12), dp(14), dp(12));
        freeBox.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        freeText = new TextView(context);
        freeText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        freeText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        freeBox.addView(freeText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        TextView subscribe = new TextView(context);
        subscribe.setText(DealsGramText.get("Subscribe"));
        subscribe.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        subscribe.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subscribe.setTypeface(AndroidUtilities.bold());
        subscribe.setPadding(dp(10), dp(6), dp(4), dp(6));
        subscribe.setOnClickListener(v -> AutoDealsPlanSheet.openCheckout(getContext(), firstUnsubscribedChannel()));
        freeBox.addView(subscribe);
        freeBox.setVisibility(View.GONE);
        column.addView(freeBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));

        // Link, only when the sheet was opened without one.
        linkBox = new FrameLayout(context);
        linkCell = new EditTextCell(context, DealsGramText.get("ProductLink"), false, false, -1, resourcesProvider);
        linkCell.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        linkCell.editText.setPadding(dp(16), dp(15), dp(16), dp(15));
        linkBox.addView(linkCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(linkBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));

        // Preview: the post as it will look in the channel, a message bubble on the chat wallpaper,
        // with the note written at its top and the link buttons below.
        FrameLayout wall = new FrameLayout(context);
        Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        if (wallpaper != null) {
            wall.setBackground(wallpaper);
        } else {
            wall.setBackgroundColor(Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .18f));
        }
        wall.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        wall.setClipToOutline(true);
        LinearLayout message = new LinearLayout(context);
        message.setOrientation(LinearLayout.VERTICAL);
        message.setPadding(dp(10), dp(10), dp(44), dp(10));
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Theme.createRoundRectDrawable(dp(14), getThemedColor(Theme.key_chat_inBubble)));
        bubble.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        bubble.setClipToOutline(true);
        previewChannel = new TextView(context);
        previewChannel.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        previewChannel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        previewChannel.setTypeface(AndroidUtilities.bold());
        previewChannel.setSingleLine(true);
        previewChannel.setEllipsize(TextUtils.TruncateAt.END);
        previewChannel.setPadding(dp(10), dp(7), dp(10), 0);
        bubble.addView(previewChannel);
        // Temporary note above the post (top_note), never stored by AutoDeals beyond this post.
        // Plain emoji only: the note is sent as text, so premium (custom) emoji would arrive as their fallback.
        noteField = new org.telegram.ui.Components.EditTextEmoji(context, root, null, org.telegram.ui.Components.EditTextEmoji.STYLE_GIFT, false, resourcesProvider) {
            // Emoji search at the top of the panel, as in Telegram's story captions (off by default in EditTextEmoji).
            @Override
            protected boolean allowSearch() {
                return true;
            }

            // Bold, italic and the rest of Telegram's formatting menu, only with a server that publishes it.
            @Override
            protected boolean allowEntities() {
                return noteHtml;
            }

            @Override
            protected void updatedEmojiExpanded() {
                root.requestLayout();
                revealNote();
            }

            @Override
            protected void onEmojiKeyboardUpdate() {
                revealNote();
            }

            @Override
            protected void createEmojiView() {
                super.createEmojiView();
                if (getEmojiView() != null) {
                    getEmojiView().plainEmojiSearch = true; // the note is plain text: no animated emoji in the results
                }
            }
        };
        noteField.setHint(DealsGramText.get("NoteInBubbleHint"));
        EditText noteEdit = noteField.getEditText();
        noteEdit.setHintTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        noteEdit.setTextColor(getThemedColor(Theme.key_chat_messageTextIn));
        noteEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        // Regular, as in the channel: the bold of the formatting menu must show.
        noteEdit.setPadding(0, dp(6), 0, dp(8));
        noteEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TOP_NOTE_MAX)});
        // The search also finds animated (custom) emoji, but the note is sent as text: keep only their plain emoji,
        // so the field shows what the channel will get.
        noteEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                org.telegram.ui.Components.AnimatedEmojiSpan[] custom = s.getSpans(0, s.length(), org.telegram.ui.Components.AnimatedEmojiSpan.class);
                if (custom.length == 0) {
                    return;
                }
                for (org.telegram.ui.Components.AnimatedEmojiSpan span : custom) {
                    s.removeSpan(span);
                }
                Emoji.replaceEmoji(s, noteEdit.getPaint().getFontMetricsInt(), false);
            }
        });
        bubble.addView(noteField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -4, 0, 0, 0));
        notePresetsScroll = new HorizontalScrollView(context);
        notePresetsScroll.setHorizontalScrollBarEnabled(false);
        notePresetsScroll.setClipToPadding(false);
        notePresetsScroll.setPadding(dp(10), 0, dp(10), dp(6));
        notePresets = new LinearLayout(context);
        notePresets.setOrientation(LinearLayout.HORIZONTAL);
        notePresetsScroll.addView(notePresets);
        notePresetsScroll.setVisibility(View.GONE);
        bubble.addView(notePresetsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        noteEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                markChosenPreset();
            }
        });
        photo = new ImageView(context);
        // Whole photo, as in the channel: width of the bubble, height from the image.
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setAdjustViewBounds(true);
        photo.setMaxHeight(dp(360));
        photo.setVisibility(View.GONE);
        bubble.addView(photo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        previewText = new TextView(context);
        previewText.setTextColor(getThemedColor(Theme.key_chat_messageTextIn));
        previewText.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        previewText.setLineSpacing(dp(2), 1f);
        previewText.setPadding(dp(10), dp(8), dp(10), dp(10));
        // Emoji drawn by Telegram appear once their images are loaded.
        org.telegram.messenger.NotificationCenter.listenEmojiLoading(previewText);
        bubble.addView(previewText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        linkPreview = new LinearLayout(context);
        linkPreview.setOrientation(LinearLayout.HORIZONTAL);
        linkPreview.setBackground(Theme.createRoundRectDrawable(dp(6), Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .10f)));
        View bar = new View(context);
        bar.setBackgroundColor(getThemedColor(Theme.key_featuredStickers_addButton));
        linkPreview.addView(bar, LayoutHelper.createLinear(3, LayoutHelper.MATCH_PARENT));
        linkPreviewImage = new ImageView(context);
        linkPreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        linkPreviewImage.setAdjustViewBounds(true);
        linkPreviewImage.setMaxHeight(dp(300));
        linkPreview.addView(linkPreviewImage, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 8, 8, 8, 8));
        linkPreview.setVisibility(View.GONE);
        bubble.addView(linkPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 0, 10, 10));
        imageFormatNote = new TextView(context);
        imageFormatNote.setText(DealsGramText.get("PreviewImageFormatNote"));
        imageFormatNote.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        imageFormatNote.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        imageFormatNote.setPadding(dp(10), 0, dp(10), dp(8));
        imageFormatNote.setVisibility(View.GONE);
        bubble.addView(imageFormatNote, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        message.addView(bubble, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        keyboardBox = new LinearLayout(context);
        keyboardBox.setOrientation(LinearLayout.VERTICAL);
        message.addView(keyboardBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wall.addView(message, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(wall, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));
        previewCard = wall;

        // What to check before publishing: store, discount, the link that goes out.
        checkText = new TextView(context);
        checkText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        checkText.setLinkTextColor(getThemedColor(Theme.key_windowBackgroundWhiteLinkText));
        checkText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        checkText.setPadding(dp(16), dp(11), dp(16), dp(11));
        checkText.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        checkText.setVisibility(View.GONE);
        column.addView(checkText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        // Bottom: optional "send as text" and the main button.
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(dp(12), dp(6), dp(12), dp(originalText != null ? 4 : 12));
        buttons.setBackgroundColor(gray);
        button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setOnClickListener(v -> onButton());
        buttons.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        if (originalText != null) {
            // The pasted link as an ordinary message, without AutoDeals.
            TextView plain = new TextView(context);
            plain.setText(DealsGramText.get("SendAsTextPlain"));
            plain.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
            plain.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            plain.setGravity(Gravity.CENTER);
            plain.setPadding(0, dp(8), 0, dp(8));
            plain.setOnClickListener(v -> {
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(originalText, dialogId, null, null, null, true, null, null, null, true, 0, 0, null, false));
                if (clearInput != null) {
                    clearInput.run();
                }
                dismiss();
            });
            buttons.addView(plain, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        root.addView(buttons, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        bulletinContainer = new FrameLayout(context);
        root.addView(bulletinContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.BOTTOM, 0, 0, 0, originalText != null ? 96 : 66));

        setCustomView(root);

        if (TextUtils.isEmpty(this.link)) {
            previewCard.setVisibility(View.GONE);
            linkCell.setText(AutoDealsPublisher.clipboardLink(context));
            button.setText(DealsGramText.get("ShowPreview"), false);
        } else {
            linkBox.setVisibility(View.GONE);
            loadChannels();
        }
    }

    /**
     * With the photo on top the note sits under it: when the emoji panel opens or grows, scroll the cards so the
     * note stays in sight above the panel.
     */
    private void revealNote() {
        if (scroll == null || noteField == null) {
            return;
        }
        // Once the cards have shrunk above the panel, which opens with the keyboard's animation.
        scroll.removeCallbacks(scrollToNote);
        scroll.postDelayed(scrollToNote, 320);
    }

    private final Runnable scrollToNote = this::scrollToNote;

    private void scrollToNote() {
        if (noteField.getParent() == null || noteField.getVisibility() != View.VISIBLE) {
            return;
        }
        android.graphics.Rect rect = new android.graphics.Rect();
        noteField.getDrawingRect(rect);
        scroll.offsetDescendantRectToMyCoords(noteField, rect);
        // The buttons at the bottom float over the cards, in the space kept by the column's bottom padding.
        int visible = scroll.getHeight() - scroll.getChildAt(0).getPaddingBottom();
        int top = scroll.getScrollY();
        if (rect.top < top) {
            scroll.smoothScrollTo(0, Math.max(0, rect.top - dp(8)));
        } else if (rect.bottom > top + visible) {
            scroll.smoothScrollTo(0, rect.bottom + dp(8) - visible);
        }
    }

    /** Back closes the emoji panel first, as in Telegram's share sheet. */
    @Override
    public void onBackPressed() {
        if (noteField.isPopupShowing()) {
            noteField.hidePopup(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void dismiss() {
        noteField.hidePopup(false);
        noteField.closeKeyboard();
        super.dismiss();
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
        noteField.onDestroy();
    }

    private void setMode(int index) {
        if (index == MODE_SCHEDULE && scheduleLocked || index == MODE_QUEUE && queueLocked) {
            modeTabs.selectTab(mode, true);
            AutoDealsPlanSheet.showFeature(getContext(), resourcesProvider,
                index == MODE_SCHEDULE ? AutoDealsPlanSheet.FEATURE_SCHEDULING : AutoDealsPlanSheet.FEATURE_QUEUE, firstUnsubscribedChannel());
            return;
        }
        if (index == MODE_SCHEDULE) {
            pickScheduleTime();
            return;
        }
        mode = index;
        modeTabs.selectTab(index, true);
        setNoteVisible(noteAllowed());
        updateButton();
    }

    /** Telegram's own "schedule message" picker; cancelling keeps the previous choice. */
    private void pickScheduleTime() {
        long pickerDialogId = previewChannelIndex >= 0
            ? channels.get(previewChannelIndex).optLong("id") + 1000000000000L
            : dialogId;
        AlertsCreator.createScheduleDatePickerDialog(getContext(), pickerDialogId, scheduleAt, 0, (notify, date, repeat) -> {
            scheduleAt = date;
            mode = MODE_SCHEDULE;
            modeTabs.selectTab(MODE_SCHEDULE, true);
            updateButton();
        }, () -> modeTabs.selectTab(mode, true), resourcesProvider).show();
    }

    /** Send link, menu ⋮, a post's menu or Share from another app. */
    AutoDealsSheet from(String entry) {
        origin = new AutoDealsApi.Origin("sheet", entry);
        return this;
    }

    static String formatScheduleTime(int unixSeconds) {
        return new SimpleDateFormat(DealsGramText.get("DateAtTime"), DealsGramText.locale()).format(new Date(unixSeconds * 1000L));
    }

    private void onButton() {
        if (publishing) {
            return;
        }
        if (linkBox.getVisibility() == View.VISIBLE) {
            String typed = linkCell.getText() != null ? linkCell.getText().toString().trim() : "";
            if (TextUtils.isEmpty(typed)) {
                error(DealsGramText.get("PasteLink"));
                return;
            }
            link = typed;
            AndroidUtilities.hideKeyboard(linkCell.editText);
            linkBox.setVisibility(View.GONE);
            previewCard.setVisibility(View.VISIBLE);
            loadChannels();
            return;
        }
        if (previewReady) {
            publish();
        }
    }

    private void updateButton() {
        if (linkBox.getVisibility() == View.VISIBLE) {
            return;
        }
        button.setText(buttonText(), true);
        button.setEnabled(previewReady && !publishing && channelWithoutQueue() == null);
    }

    private String buttonText() {
        int count = Math.max(1, selected.size());
        if (mode == MODE_SCHEDULE) {
            return count == 1 ? DealsGramText.format("ScheduleAt", formatScheduleTime(scheduleAt))
                : DealsGramText.format("ScheduleAtChannels", formatScheduleTime(scheduleAt), count);
        }
        if (mode == MODE_QUEUE) {
            JSONObject missing = channelWithoutQueue();
            if (missing != null) {
                return DealsGramText.format("QueueOffOn", AutoDealsPublisher.channelTitle(missing));
            }
            return count == 1 ? DealsGramText.get("AddToQueue") : DealsGramText.format("AddToQueueChannels", count);
        }
        if (count == 1 && !selected.isEmpty()) {
            JSONObject free = AutoDealsPublisher.freePlan(channels.get(selected.get(0)));
            if (free != null && free.optInt("remaining") > 0) {
                return DealsGramText.plural("PublishNowLeft", free.optInt("remaining"));
            }
        }
        return count == 1 ? DealsGramText.get("PublishNow") : DealsGramText.format("PublishOnChannels", count);
    }

    // --- Note presets ----------------------------------------------------------

    private void setNoteVisible(boolean visible) {
        noteField.setVisibility(visible ? View.VISIBLE : View.GONE);
        List<String> presets = previewChannelIndex >= 0 && previewChannelIndex < channels.size()
            ? presetsByChannel.get(channels.get(previewChannelIndex).optLong("id")) : null;
        notePresetsScroll.setVisibility(visible && presets != null && !presets.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** The previewed channel's preset texts, read from its settings (tags.customTextPresets, one per line). */
    private void loadNotePresets(long channelId) {
        if (presetsByChannel.containsKey(channelId)) {
            showNotePresets(channelId);
            return;
        }
        // Its own queue: the preview waits on the global one for the post prepared before Send.
        PRESETS_QUEUE.postRunnable(() -> {
            List<String> presets = new ArrayList<>();
            try {
                JSONObject params = new JSONObject();
                params.put("channel_id", channelId);
                Object data = AutoDealsApi.getInstance(account).call("get_settings", params, origin);
                Object tags = data instanceof JSONObject ? ((JSONObject) data).opt("tags") : null;
                JSONObject tagsObject = tags instanceof JSONObject ? (JSONObject) tags
                    : tags instanceof String && !TextUtils.isEmpty((String) tags) ? new JSONObject((String) tags) : null;
                String raw = tagsObject != null ? tagsObject.optString("customTextPresets", "") : "";
                for (String line : raw.split("\\r\\n|\\r|\\n")) {
                    String text = line.trim();
                    if (text.length() > TOP_NOTE_MAX) {
                        text = text.substring(0, TOP_NOTE_MAX).trim();
                    }
                    if (!text.isEmpty() && !presets.contains(text)) {
                        presets.add(text);
                    }
                }
            } catch (Exception ignore) {
                // No presets: the note is written by hand, as before.
            }
            AndroidUtilities.runOnUIThread(() -> {
                presetsByChannel.put(channelId, presets);
                showNotePresets(channelId);
            });
        });
    }

    private void showNotePresets(long channelId) {
        if (previewChannelIndex < 0 || previewChannelIndex >= channels.size()
            || channels.get(previewChannelIndex).optLong("id") != channelId) {
            return; // another channel is previewed by now
        }
        notePresets.removeAllViews();
        List<String> presets = presetsByChannel.get(channelId);
        if (presets != null) {
            for (String preset : presets) {
                TextView chip = new TextView(getContext());
                chip.setTag(preset);
                chip.setText(Emoji.replaceEmoji(preset, chip.getPaint().getFontMetricsInt(), false));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setMaxWidth(dp(220));
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(12), 0, dp(12), 0);
                chip.setOnClickListener(v -> {
                    EditText edit = noteField.getEditText();
                    // A second tap on the chosen preset clears the note again.
                    String next = preset.equals(edit.getText().toString().trim()) ? "" : preset;
                    edit.setText(Emoji.replaceEmoji(next, edit.getPaint().getFontMetricsInt(), false));
                    edit.setSelection(edit.length());
                });
                notePresets.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
            }
        }
        markChosenPreset();
        setNoteVisible(noteField.getVisibility() == View.VISIBLE);
    }

    /** The chip that matches the note is filled, the others tinted. */
    private void markChosenPreset() {
        if (notePresets == null) {
            return;
        }
        String note = noteField.getText() != null ? noteField.getText().toString().trim() : "";
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        for (int i = 0; i < notePresets.getChildCount(); i++) {
            TextView chip = (TextView) notePresets.getChildAt(i);
            boolean chosen = note.equals(chip.getTag());
            chip.setBackground(Theme.createRoundRectDrawable(dp(16),
                chosen ? accent : androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1f)));
            chip.setTextColor(chosen ? 0xffffffff : accent);
        }
    }

    /**
     * The note above the post is part of the subscription: hidden when a selected channel is Free, and in queue
     * mode, where V2 builds the queued post itself at publication time.
     */
    private boolean noteAllowed() {
        if (mode == MODE_QUEUE) {
            return false;
        }
        for (int index : selected) {
            if (!AutoDealsPublisher.hasSubscription(channels.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** The first selected channel without a subscription (Free or expired), for the checkout link; 0 if none. */
    private long firstUnsubscribedChannel() {
        for (int index : selected) {
            if (channels.get(index).optJSONObject("subscription") == null) {
                return channels.get(index).optLong("id");
            }
        }
        return 0;
    }

    /**
     * Free plan of the selected channels: posts left today, and locks on what the plan does not include
     * (scheduling, queue), which open AutoDealsPlanSheet instead of failing on the server.
     */
    private void updatePlan() {
        JSONObject free = null;
        boolean allSchedule = true;
        boolean anySubscription = false;
        for (int index : selected) {
            JSONObject channel = channels.get(index);
            if (free == null) {
                free = AutoDealsPublisher.freePlan(channel);
            }
            allSchedule &= AutoDealsPublisher.canSchedule(channel);
        }
        for (JSONObject channel : channels) {
            anySubscription |= channel.optJSONObject("subscription") != null;
        }
        if (free != null && free.optBoolean("manual_posts", true)) {
            freeText.setText(free.optInt("remaining") > 0
                ? AndroidUtilities.replaceTags(DealsGramText.plural("FreeCard", free.optInt("remaining"), free.optInt("daily_limit")))
                : DealsGramText.format("LimitTitle", free.optInt("daily_limit")) + ".");
            freeBox.setVisibility(View.VISIBLE);
        } else {
            freeBox.setVisibility(View.GONE);
        }
        scheduleLocked = !allSchedule;
        scheduleTab.set(scheduleLocked ? R.drawable.outline_header_lock_24 : R.drawable.msg_calendar2, DealsGramText.get("Schedule"));
        if (!queueTabAdded && !anySubscription) {
            // Only Free channels: the queue is shown as something the subscription adds.
            queueTab = modeTabs.addTab(R.drawable.outline_header_lock_24, DealsGramText.get("AddToQueue"), this::setMode);
            modeTabs.selectTab(mode, false);
            queueTabAdded = true;
            queueLocked = true;
        }
        if (mode == MODE_SCHEDULE && scheduleLocked) {
            mode = MODE_NOW;
            modeTabs.selectTab(MODE_NOW, true);
        }
        if (previewReady) {
            setNoteVisible(noteAllowed());
        }
    }

    /** In queue mode, a selected channel without Autoposting V2: it has no queue, so the button stays off. */
    private JSONObject channelWithoutQueue() {
        if (mode != MODE_QUEUE) {
            return null;
        }
        for (int index : selected) {
            if (!AutoDealsPublisher.hasAutopostingQueue(channels.get(index))) {
                return channels.get(index);
            }
        }
        return null;
    }

    private boolean queueTabAdded;

    private void addQueueTabIfUseful() {
        if (queueTabAdded) {
            return;
        }
        for (JSONObject channel : channels) {
            if (AutoDealsPublisher.hasAutopostingQueue(channel)) {
                modeTabs.addTab(R.drawable.msg_list, DealsGramText.get("AddToQueue"), this::setMode);
                modeTabs.selectTab(mode, false);
                queueTabAdded = true;
                return;
            }
        }
    }

    // --- Loading -------------------------------------------------------------

    private void loadChannels() {
        showPreviewLoading();
        Utilities.globalQueue.postRunnable(() -> {
            // Loaded a moment ago, together with the post prepared while the link was in the field.
            List<JSONObject> prepared = AutoDealsPublisher.preparedChannels(account, dialogId, link);
            List<JSONObject> list;
            if (prepared != null) {
                list = prepared;
            } else {
                try {
                    list = AutoDealsPublisher.loadChannels(account);
                } catch (AutoDealsApi.AutoDealsException e) {
                    AndroidUtilities.runOnUIThread(() -> failPreview(e.text));
                    return;
                }
            }
            // Right after launch the chats may not be in memory yet: read them from Telegram's local
            // database, so the chips can show the channel photos.
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
                channels = list;
                addQueueTabIfUseful();
                if (channels.isEmpty()) {
                    // Not an AutoDeals user yet: how to link a channel, instead of an error.
                    dismiss();
                    AutoDealsPlanSheet.showConnect(getContext(), resourcesProvider);
                    return;
                }
                // The open channel if it is linked; otherwise (shared from another app) the last ones used.
                long current = AutoDealsPublisher.botApiChannelId(dialogId);
                List<Long> last = AutoDealsPublisher.lastChannels(account);
                selected.clear();
                for (int i = 0; i < channels.size(); i++) {
                    if (channels.get(i).optLong("id") == current) {
                        selected.add(i);
                    }
                }
                if (selected.isEmpty()) {
                    for (int i = 0; i < channels.size(); i++) {
                        if (last.contains(channels.get(i).optLong("id"))) {
                            selected.add(i);
                        }
                    }
                }
                if (selected.isEmpty()) {
                    // First use: a channel that can actually publish, not one blocked by its plan.
                    for (int i = 0; i < channels.size() && selected.isEmpty(); i++) {
                        if (AutoDealsPublisher.canPublish(channels.get(i))) {
                            selected.add(i);
                        }
                    }
                }
                if (selected.isEmpty()) {
                    selected.add(0);
                }
                channelPill.setVisibility(channels.size() > 1 ? View.VISIBLE : View.GONE);
                updatePill();
                updatePlan();
                loadPreview();
            });
        });
    }

    /**
     * The channels to publish on, as Telegram's chat selection: photo with a round check, name and plan.
     * "All channels" on top ticks every one; unticking it goes back to the open channel only.
     */
    private void pickChannels() {
        if (publishing || channels.size() < 2) {
            return;
        }
        Context context = getContext();
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(4));
        boolean[] on = new boolean[channels.size()];
        for (int index : selected) {
            on[index] = true;
        }
        List<CheckBox2> checks = new ArrayList<>();
        int home = homeChannel();

        // "All channels": up to three photos side by side, like the pill.
        FrameLayout stack = new FrameLayout(context);
        for (int i = Math.min(3, channels.size()) - 1; i >= 0; i--) {
            BackupImageView avatar = avatar(channels.get(i), 15);
            stack.addView(avatar, LayoutHelper.createFrame(30, 30, Gravity.LEFT | Gravity.CENTER_VERTICAL, i * 6, 0, 0, 0));
        }
        CheckBox2 allCheck = check(context, selected.size() == channels.size());
        LinearLayout allRow = row(context, stack, allCheck, DealsGramText.format("AllChannelsCount", channels.size()), DealsGramText.get("AllChannelsHint"));
        list.addView(allRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        View divider = new View(context);
        divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        list.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 72, 2, 0, 2));

        for (int i = 0; i < channels.size(); i++) {
            int index = i;
            JSONObject channel = channels.get(i);
            CheckBox2 check = check(context, on[i]);
            checks.add(check);
            LinearLayout channelRowView = row(context, avatar(channel, 21), check, AutoDealsPublisher.channelTitle(channel), AutoDealsPlanSheet.planLine(channel));
            if (channel.optJSONObject("subscription") == null) {
                // Free or expired: the way to a subscription, without leaving the list.
                TextView subscribe = new TextView(context);
                subscribe.setText(DealsGramText.get("Subscribe"));
                subscribe.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
                subscribe.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                subscribe.setTypeface(AndroidUtilities.bold());
                subscribe.setGravity(Gravity.CENTER);
                subscribe.setPadding(dp(12), 0, dp(12), 0);
                subscribe.setBackground(Theme.createRoundRectDrawable(dp(14), getThemedColor(Theme.key_featuredStickers_addButton)));
                ScaleStateListAnimator.apply(subscribe, .1f, 1.5f);
                subscribe.setOnClickListener(v -> AutoDealsPlanSheet.openCheckout(getContext(), channel.optLong("id")));
                channelRowView.addView(subscribe, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            }
            channelRowView.setOnClickListener(v -> {
                on[index] = !on[index];
                check.setChecked(on[index], true);
                boolean every = true;
                for (boolean b : on) {
                    every &= b;
                }
                allCheck.setChecked(every, true);
            });
            list.addView(channelRowView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        }
        allRow.setOnClickListener(v -> {
            boolean every = !allCheck.isChecked();
            allCheck.setChecked(every, true);
            for (int i = 0; i < on.length; i++) {
                on[i] = every || i == home;
                checks.get(i).setChecked(on[i], true);
            }
        });

        ScrollView scrollList = new ScrollView(context);
        scrollList.addView(list);
        new AlertDialog.Builder(context, resourcesProvider)
            .setTitle(DealsGramText.get("WhichChannels"))
            .setView(scrollList)
            .setPositiveButton(DealsGramText.get("Done"), (dialog, which) -> {
                List<Integer> picked = new ArrayList<>();
                for (int i = 0; i < on.length; i++) {
                    if (on[i]) {
                        picked.add(i);
                    }
                }
                if (picked.isEmpty()) {
                    picked.add(home);
                }
                selected.clear();
                selected.addAll(picked);
                updatePill();
                updatePlan();
                updateButton();
                if (previewChannelIndex != firstSelected()) {
                    loadPreview();
                } else {
                    updatePreviewLabel();
                }
            })
            .setNegativeButton(DealsGramText.get("Cancel"), null)
            .show();
    }

    /** One line of the channel list: photo with the check on its corner, name, and a grey line under it. */
    private LinearLayout row(Context context, View picture, CheckBox2 check, String name, String subtitle) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setBackground(Theme.getSelectorDrawable(false));
        FrameLayout photoBox = new FrameLayout(context);
        photoBox.addView(picture, picture instanceof BackupImageView
            ? LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL)
            : LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        photoBox.addView(check, LayoutHelper.createFrame(24, 24, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, -3, -1));
        row.addView(photoBox, LayoutHelper.createLinear(48, 46, 0, 0, 14, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(name);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = new TextView(context);
            sub.setText(subtitle);
            sub.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    /** Telegram's round selection check, drawn as a ring on the photo's corner. */
    private CheckBox2 check(Context context, boolean checked) {
        CheckBox2 check = new CheckBox2(context, 21, resourcesProvider);
        check.setColor(-1, Theme.key_dialogBackground, Theme.key_checkboxCheck);
        check.setDrawUnchecked(false);
        check.setDrawBackgroundAsArc(3);
        check.setChecked(checked, false);
        return check;
    }

    private BackupImageView avatar(JSONObject channel, int radiusDp) {
        return AutoDealsPublisher.channelAvatar(getContext(), account, channel, radiusDp);
    }

    /** The open channel when it is linked, otherwise the first selected one. */
    private int homeChannel() {
        long current = AutoDealsPublisher.botApiChannelId(dialogId);
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).optLong("id") == current) {
                return i;
            }
        }
        return selected.isEmpty() ? 0 : selected.get(0);
    }

    /** The previewed channel: the one this chat belongs to while it is on, otherwise the first one on. */
    private int firstSelected() {
        long current = AutoDealsPublisher.botApiChannelId(dialogId);
        for (int index : selected) {
            if (channels.get(index).optLong("id") == current) {
                return index;
            }
        }
        return selected.get(0);
    }

    /** Photos (up to three, overlapping) and "Name", "Name + 2" or "All · 5". */
    private void updatePill() {
        if (channels.size() < 2) {
            return;
        }
        pillAvatars.removeAllViews();
        int shown = Math.min(3, selected.size());
        for (int i = 0; i < shown; i++) {
            BackupImageView avatar = avatar(channels.get(selected.get(i)), 12);
            pillAvatars.addView(avatar, 0, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, i * 14, 0, 0, 0));
        }
        String first = AutoDealsPublisher.channelTitle(channels.get(firstSelected()));
        if (selected.size() == channels.size()) {
            pillText.setText(DealsGramText.format("AllChannelsShort", channels.size()));
        } else if (selected.size() > 1) {
            pillText.setText(first + " + " + (selected.size() - 1));
        } else {
            pillText.setText(first);
        }
    }

    private void updatePreviewLabel() {
        previewChannel.setText(AutoDealsPublisher.channelTitle(channels.get(previewChannelIndex)));
    }

    private void loadPreview() {
        previewChannelIndex = firstSelected();
        JSONObject channel = channels.get(previewChannelIndex);
        long channelId = channel.optLong("id");
        String currentLink = link;
        int token = ++requestToken;
        updatePreviewLabel();
        showPreviewLoading();
        loadNotePresets(channelId);
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject p;
            JSONObject c;
            JSONObject rendered;
            Bitmap bitmap;
            // Prepared while the link was in the field (channels with a subscription): no second request.
            AutoDealsPublisher.Prepared ready = currentLink != null
                ? AutoDealsPublisher.takePrepared(account, channelId, currentLink.trim(), false) : null;
            if (ready != null) {
                p = ready.product;
                c = ready.customData;
                rendered = ready.rendered;
                bitmap = ready.bitmap;
            } else {
                try {
                    AutoDealsApi api = AutoDealsApi.getInstance(account);
                    p = AutoDealsPublisher.scrape(api, channelId, currentLink, origin);
                    c = AutoDealsPublisher.customData(p);
                    JSONObject params = new JSONObject();
                    params.put("channel_id", channelId);
                    params.put("offer_id", p.opt("offer_id"));
                    params.put("custom_data", c);
                    Object data = api.call("preview_post_message", params, origin);
                    rendered = data instanceof JSONObject ? (JSONObject) data : new JSONObject();
                } catch (AutoDealsApi.AutoDealsException e) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (token == requestToken) failPreview(e.text);
                    });
                    return;
                } catch (Exception e) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (token == requestToken) failPreview(DealsGramText.get("PreviewFailed"));
                    });
                    return;
                }
                String mediaType = rendered.optString("media_type");
                byte[] image = "media".equals(mediaType) || "attached".equals(mediaType)
                    ? AutoDealsApi.download(rendered.optString("product_image", null)) : null;
                bitmap = image != null ? BitmapFactory.decodeByteArray(image, 0, image.length) : null;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (token != requestToken) {
                    return;
                }
                product = p;
                customData = c;
                showPreview(rendered, bitmap);
                String warning = p.optString("warning_message", "");
                if (!TextUtils.isEmpty(warning) && !"null".equals(warning)) {
                    info(warning);
                }
            });
        });
    }

    /** Shimmer lines, as the AI Editor shows while it works. */
    private void showPreviewLoading() {
        previewReady = false;
        photo.setVisibility(View.GONE);
        linkPreview.setVisibility(View.GONE);
        imageFormatNote.setVisibility(View.GONE);
        keyboardBox.removeAllViews();
        checkText.setVisibility(View.GONE);
        SpannableStringBuilder loading = new SpannableStringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) loading.append("\n");
            int from = loading.length();
            loading.append(DealsGramText.get("Loading"));
            loading.setSpan(new LoadingSpan(previewText, dp((int) (Math.random() * 50)), 0, resourcesProvider)
                .setHeight(dp(6)).setAlpha(0.5f).setFullWidth(true), from, loading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        previewText.setText(loading);
        button.setText(buttonText(), false);
        button.setEnabled(false);
    }

    private void showPreview(JSONObject rendered, Bitmap bitmap) {
        org.json.JSONArray noteFormats = rendered.optJSONArray("top_note_formats");
        noteHtml = false;
        for (int i = 0; noteFormats != null && i < noteFormats.length(); i++) {
            noteHtml |= "html".equals(noteFormats.optString(i));
        }
        String html = rendered.optString("text", "");
        // Telegram HTML uses plain newlines, which Html.fromHtml would collapse into spaces.
        CharSequence text = TextUtils.isEmpty(html) ? product.optString("title", link) : Html.fromHtml(html.replace("\n", "<br>"));
        if (text.length() > PREVIEW_MAX_CHARS) {
            text = text.subSequence(0, PREVIEW_MAX_CHARS) + "…";
        }
        previewText.setText(Emoji.replaceEmoji(text, previewText.getPaint().getFontMetricsInt(), false));
        // As the channel's format places it: a photo above the text ("media") or a link preview below it
        // ("attached"); "none" has no image.
        if (bitmap != null && "attached".equals(rendered.optString("media_type"))) {
            linkPreviewImage.setImageBitmap(bitmap);
            linkPreview.setVisibility(View.VISIBLE);
        } else if (bitmap != null) {
            photo.setImageBitmap(bitmap);
            photo.setVisibility(View.VISIBLE);
        }
        // In a post with the photo on top the note is part of the caption: under the photo, above the title.
        boolean noteUnderPhoto = photo.getVisibility() == View.VISIBLE;
        android.view.ViewGroup bubble = (android.view.ViewGroup) photo.getParent();
        if ((bubble.indexOfChild(noteField) > bubble.indexOfChild(photo)) != noteUnderPhoto) {
            android.view.ViewGroup.LayoutParams noteParams = noteField.getLayoutParams();
            android.view.ViewGroup.LayoutParams presetParams = notePresetsScroll.getLayoutParams();
            bubble.removeView(noteField);
            bubble.removeView(notePresetsScroll);
            int at = bubble.indexOfChild(photo) + (noteUnderPhoto ? 1 : 0);
            bubble.addView(noteField, at, noteParams);
            bubble.addView(notePresetsScroll, at + 1, presetParams);
        }
        // The real image is the channel's image format, drawn by AutoDeals when it publishes; the preview has
        // only the product photo (preview_post_message skips media).
        imageFormatNote.setVisibility(bitmap != null && !rendered.isNull("image_format_id") && rendered.optLong("image_format_id") > 0 ? View.VISIBLE : View.GONE);
        setNoteVisible(noteAllowed());
        showKeyboard(rendered.optJSONArray("keyboard"));
        showCheck();
        previewReady = true;
        updateButton();
    }

    /** The post's link buttons under the bubble, as Telegram shows inline keyboards. */
    private void showKeyboard(org.json.JSONArray rows) {
        keyboardBox.removeAllViews();
        if (rows == null) {
            return;
        }
        for (int r = 0; r < rows.length(); r++) {
            org.json.JSONArray buttons = rows.optJSONArray(r);
            if (buttons == null || buttons.length() == 0) {
                continue;
            }
            LinearLayout row = new LinearLayout(getContext());
            for (int b = 0; b < buttons.length(); b++) {
                JSONObject spec = buttons.optJSONObject(b);
                if (spec == null) {
                    continue;
                }
                TextView key = new TextView(getContext());
                key.setText(Emoji.replaceEmoji(spec.optString("text"), key.getPaint().getFontMetricsInt(), false));
                key.setTextColor(getThemedColor(Theme.key_chat_serviceText));
                key.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                key.setTypeface(AndroidUtilities.bold());
                key.setGravity(Gravity.CENTER);
                key.setSingleLine(true);
                key.setEllipsize(TextUtils.TruncateAt.END);
                key.setPadding(dp(6), dp(9), dp(6), dp(9));
                key.setBackground(Theme.createRoundRectDrawable(dp(10), getThemedColor(Theme.key_chat_serviceBackground)));
                row.addView(key, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, b == 0 ? 0 : 2, 0, b == buttons.length() - 1 ? 0 : 2, 0));
            }
            keyboardBox.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
    }

    /** "Amazon IT · -38% · Minimo storico · amzlink.to/…": what to check before publishing. */
    private void showCheck() {
        if (product == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        String store = product.optString("store", "");
        String nation = product.optString("nation", "");
        if (!TextUtils.isEmpty(store) && !"null".equals(store)) {
            String name = AutoDealsSearchFilters.STORES.containsKey(store.toUpperCase(Locale.US)) ? AutoDealsSearchFilters.STORES.get(store.toUpperCase(Locale.US)) : store;
            parts.add(TextUtils.isEmpty(nation) || "null".equals(nation) ? name : name + " " + nation.toUpperCase(Locale.US));
        }
        double discount = product.optDouble("discount_percentage", Double.NaN);
        if (Double.isNaN(discount)) {
            double deal = product.optDouble("deal_price", Double.NaN);
            double original = product.optDouble("original_price", Double.NaN);
            if (!Double.isNaN(deal) && !Double.isNaN(original) && original > deal) {
                // Same rounding as the post's {discountPerc} and AutoDealsMultiSheet.
                discount = 100 - Math.floor(deal * 100 / original);
            }
        }
        if (!Double.isNaN(discount) && discount >= 1) {
            parts.add("-" + Math.round(discount) + "%");
        }
        if (product.optBoolean("is_lowest_price")) {
            parts.add(DealsGramText.get("AllTimeLowLower"));
        }
        String coupon = product.optString("coupon", "");
        if (!TextUtils.isEmpty(coupon) && !"null".equals(coupon)) {
            parts.add(DealsGramText.format("CouponValue", coupon));
        }
        String outLink = product.optString("short_link", "");
        if (TextUtils.isEmpty(outLink) || "null".equals(outLink)) {
            outLink = product.optString("link", "");
        }
        if (!TextUtils.isEmpty(outLink) && !"null".equals(outLink)) {
            String shown = outLink.replaceFirst("^https?://(www\\.)?", "");
            parts.add("🔗 " + (shown.length() > 32 ? shown.substring(0, 31) + "…" : shown));
        }
        checkText.setText(TextUtils.join("  ·  ", parts));
        checkText.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void failPreview(String message) {
        previewText.setText(message);
        // No post to write a note on.
        setNoteVisible(false);
        previewReady = false;
        updateButton();
        if (originalText == null) {
            error(message);
        }
    }

    // --- Publishing ----------------------------------------------------------

    private void publish() {
        List<JSONObject> targets = new ArrayList<>();
        targets.add(channels.get(previewChannelIndex));
        for (int index : selected) {
            if (index != previewChannelIndex) {
                targets.add(channels.get(index));
            }
        }
        if (mode == MODE_QUEUE) {
            enqueue(targets, false);
            return;
        }
        for (JSONObject target : targets) {
            JSONObject free = AutoDealsPublisher.freePlan(target);
            if (free != null && free.optInt("remaining") <= 0) {
                // Free posts of the day are over: the way to a subscription, instead of the server's error.
                AutoDealsPlanSheet.showLimit(getContext(), resourcesProvider, free, target.optLong("id"));
                return;
            }
        }
        // create_post takes "now" or a date; the offset lets the server convert it to its own time zone.
        String schedule = mode == MODE_SCHEDULE ? new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(scheduleAt * 1000L)) : "now";
        String note = noteAllowed() && noteField.getText() != null ? noteField.getText().toString().trim() : "";
        String noteMarkup = noteHtml && !note.isEmpty() ? AutoDealsDraft.noteHtml(account, noteField.getText()) : null;
        publishing = true;
        button.setLoading(true);
        Utilities.globalQueue.postRunnable(() -> {
            AutoDealsApi api = AutoDealsApi.getInstance(account);
            List<String> failures = new ArrayList<>();
            List<Long> succeeded = new ArrayList<>();
            boolean noteIgnored = false;
            JSONObject limitReached = null;
            for (int i = 0; i < targets.size(); i++) {
                JSONObject target = targets.get(i);
                try {
                    JSONObject p = product;
                    JSONObject c = customData;
                    if (i > 0) {
                        // Affiliate tag and format change per channel: read the link again for it.
                        p = AutoDealsPublisher.scrape(api, target.optLong("id"), link, origin);
                        c = AutoDealsPublisher.customData(p);
                    }
                    Object post = api.call("create_post", AutoDealsPublisher.createParams(target, p, c, schedule, note, noteMarkup), origin);
                    // A server not yet updated ignores top_note and creates the post without it.
                    noteIgnored |= !TextUtils.isEmpty(note) && !(post instanceof JSONObject && ((JSONObject) post).has("top_note"));
                    succeeded.add(target.optLong("id"));
                } catch (AutoDealsApi.AutoDealsException e) {
                    failures.add(AutoDealsPublisher.channelTitle(target) + ": " + e.text);
                    if ("free_plan_daily_limit".equals(e.code)) {
                        JSONObject free = AutoDealsPublisher.freePlan(target);
                        limitReached = free != null ? free : new JSONObject();
                    }
                } catch (Exception e) {
                    failures.add(DealsGramText.format("ChannelPublishFailed", AutoDealsPublisher.channelTitle(target)));
                }
            }
            int done = targets.size() - failures.size();
            boolean ignored = noteIgnored;
            JSONObject limit = limitReached;
            AndroidUtilities.runOnUIThread(() -> {
                publishing = false;
                button.setLoading(false);
                if (limit != null && targets.size() == 1) {
                    AutoDealsPlanSheet.showLimit(getContext(), resourcesProvider, limit, targets.get(0).optLong("id"));
                    return;
                }
                if (!failures.isEmpty()) {
                    // Keep only the channels that failed, so trying again cannot post twice elsewhere.
                    if (!succeeded.isEmpty()) {
                        for (int i = selected.size() - 1; i >= 0; i--) {
                            if (succeeded.contains(channels.get(selected.get(i)).optLong("id"))) {
                                selected.remove(i);
                            }
                        }
                        updatePill();
                        if (previewChannelIndex != firstSelected()) {
                            loadPreview();
                        }
                    }
                    updateButton();
                    String prefix = targets.size() > 1 ? DealsGramText.format("DoneOnCount", done, targets.size()) + " " : "";
                    error(prefix + TextUtils.join(" · ", failures));
                    return;
                }
                List<Long> used = new ArrayList<>();
                for (JSONObject target : targets) {
                    used.add(target.optLong("id"));
                }
                AutoDealsPublisher.saveLastChannels(account, used);
                if (clearInput != null) {
                    clearInput.run();
                }
                dismiss();
                String message = ignored
                    ? DealsGramText.get("NoteNotSupported")
                    // "now" schedules the post at the current minute: the AutoDeals worker publishes it.
                    : "now".equals(schedule) ? (done > 1 ? DealsGramText.format("PublishingSoonChannels", done) : DealsGramText.get("PublishingSoon"))
                    : done > 1 ? DealsGramText.format("ScheduledForChannels", done, formatScheduleTime(scheduleAt))
                    : DealsGramText.format("ScheduledFor", formatScheduleTime(scheduleAt));
                BulletinFactory.of(fragment).createSimpleBulletin(ignored ? R.raw.info : R.raw.contact_check, message).show();
            });
        });
    }

    /** Queue the selected products, requesting confirmation for duplicates when needed. */
    private void enqueue(List<JSONObject> targets, boolean confirmDuplicates) {
        publishing = true;
        button.setLoading(true);
        Utilities.globalQueue.postRunnable(() -> {
            AutoDealsApi api = AutoDealsApi.getInstance(account);
            List<String> failures = new ArrayList<>();
            List<JSONObject> duplicates = new ArrayList<>();
            List<Long> succeeded = new ArrayList<>();
            int queueLength = 0;
            for (int i = 0; i < targets.size(); i++) {
                JSONObject target = targets.get(i);
                try {
                    boolean previewed = target.optLong("id") == channels.get(previewChannelIndex).optLong("id");
                    JSONObject p = previewed ? product : AutoDealsPublisher.scrape(api, target.optLong("id"), link, origin);
                    long offerId = p.optLong("offer_id");
                    if (offerId <= 0) {
                        failures.add(DealsGramText.format("ChannelCannotQueue", AutoDealsPublisher.channelTitle(target)));
                        continue;
                    }
                    JSONObject params = new JSONObject();
                    params.put("channel_id", target.optLong("id"));
                    params.put("offer_id", offerId);
                    params.put("offer_action", "add_curated");
                    if (confirmDuplicates) {
                        params.put("confirm_duplicates", true);
                    }
                    Object data = api.call("autoposting_v2_offer_action", params, origin);
                    if (data instanceof JSONObject && ((JSONObject) data).optJSONArray("items") != null) {
                        queueLength = ((JSONObject) data).optJSONArray("items").length();
                    }
                    succeeded.add(target.optLong("id"));
                } catch (AutoDealsApi.AutoDealsException e) {
                    if ("duplicate_product_confirmation_required".equals(e.code)) {
                        duplicates.add(target);
                    } else {
                        failures.add(AutoDealsPublisher.channelTitle(target) + ": " + e.text);
                    }
                } catch (Exception e) {
                    failures.add(DealsGramText.format("ChannelNotQueued", AutoDealsPublisher.channelTitle(target)));
                }
            }
            int position = queueLength;
            AndroidUtilities.runOnUIThread(() -> {
                publishing = false;
                button.setLoading(false);
                // Channels already done leave the selection, so a retry cannot queue them twice.
                for (int i = selected.size() - 1; i >= 0 && !succeeded.isEmpty(); i--) {
                    if (succeeded.contains(channels.get(selected.get(i)).optLong("id")) && selected.size() > 1) {
                        selected.remove(i);
                    }
                }
                if (!duplicates.isEmpty() && failures.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (JSONObject channel : duplicates) {
                        names.add(AutoDealsPublisher.channelTitle(channel));
                    }
                    new AlertDialog.Builder(getContext(), resourcesProvider)
                        .setTitle(DealsGramText.get("RecentlyPublished"))
                        .setMessage(DealsGramText.format("DuplicateOnChannels", TextUtils.join(", ", names)))
                        .setPositiveButton(DealsGramText.get("AddToQueue"), (dialog, which) -> enqueue(duplicates, true))
                        .setNegativeButton(DealsGramText.get("Cancel"), null)
                        .show();
                    updatePill();
                    updateButton();
                    return;
                }
                if (!failures.isEmpty() || !duplicates.isEmpty()) {
                    updatePill();
                    updateButton();
                    for (JSONObject channel : duplicates) {
                        failures.add(DealsGramText.format("ChannelRecentlyPublished", AutoDealsPublisher.channelTitle(channel)));
                    }
                    error(TextUtils.join(" · ", failures));
                    return;
                }
                List<Long> used = new ArrayList<>(succeeded);
                AutoDealsPublisher.saveLastChannels(account, used);
                if (clearInput != null) {
                    clearInput.run();
                }
                dismiss();
                String message = succeeded.size() > 1 ? DealsGramText.format("QueuedChannels", succeeded.size())
                    : position > 0 ? DealsGramText.format("QueuedPosition", position)
                    : DealsGramText.get("Queued");
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, message).show();
            });
        });
    }

    private void error(String text) {
        BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(text).show();
    }

    private void info(String text) {
        BulletinFactory.of(bulletinContainer, resourcesProvider).createSimpleBulletin(R.raw.info, text).show();
    }
}
