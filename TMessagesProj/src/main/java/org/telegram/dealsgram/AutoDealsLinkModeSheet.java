package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Impostazioni → AutoDeals → "Quando invii un link": the two ways side by side, each with a short looping
 * recording of what happens (assets/dealsgram/link_mode_*.mp4, recorded from the app on the demo channel).
 * The post in the field is part of the subscription: without a subscribed channel it shows a lock and opens the
 * plan sheet instead of being chosen.
 */
public class AutoDealsLinkModeSheet extends BottomSheet {

    private static final float VIDEO_RATIO = 960f / 432f;

    private final Option sheetOption;
    private final Option fieldOption;

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider, Runnable onChanged) {
        new AutoDealsLinkModeSheet(context, resourcesProvider, onChanged).show();
    }

    private AutoDealsLinkModeSheet(Context context, Theme.ResourcesProvider resourcesProvider, Runnable onChanged) {
        super(context, false, resourcesProvider);
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(DealsGramText.get("LinkModeSheetTitle"));
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 14));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean field = AutoDealsDraft.fieldMode();
        boolean locked = !AutoDealsPublisher.anySubscription(UserConfig.selectedAccount);
        sheetOption = new Option(context, "link_mode_sheet.mp4", "LinkModeSheet", "LinkModeSheetInfo", !field, false);
        fieldOption = new Option(context, "link_mode_field.mp4", "LinkModeField", "LinkModeFieldInfo", field, locked);
        row.addView(sheetOption, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));
        row.addView(fieldOption, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 6, 0, 0, 0));
        sheetOption.setOnClickListener(v -> select(false));
        fieldOption.setOnClickListener(v -> {
            if (locked) {
                AutoDealsPlanSheet.showFeature(context, resourcesProvider, AutoDealsPlanSheet.FEATURE_FIELD, 0);
            } else {
                select(true);
            }
        });
        content.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 0));

        ButtonWithCounterView done = new ButtonWithCounterView(context, resourcesProvider).setRound();
        done.setText(DealsGramText.get("Done"), false);
        done.setOnClickListener(v -> {
            boolean chosen = fieldOption.selected;
            if (chosen != AutoDealsDraft.fieldMode()) {
                AutoDealsDraft.setFieldMode(chosen);
                if (onChanged != null) {
                    onChanged.run();
                }
            }
            dismiss();
        });
        content.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14, 16, 14, 12));
        setCustomView(content);
    }

    private void select(boolean field) {
        sheetOption.setSelected(!field);
        fieldOption.setSelected(field);
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
        sheetOption.video.release();
        fieldOption.video.release();
    }

    /**
     * One way: the recording, then a radio with its name and what it does. A blue frame marks the chosen one;
     * a locked way has a lock instead of the radio and says it comes with the subscription.
     */
    private class Option extends LinearLayout {
        final LoopingVideo video;
        private final RadioButton radio;
        private final GradientDrawable frame = new GradientDrawable();
        boolean selected;

        Option(Context context, String asset, String name, String info, boolean selected, boolean locked) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(6), dp(6), dp(6), dp(10));
            frame.setCornerRadius(dp(16));
            setBackground(frame);

            video = new LoopingVideo(context, "dealsgram/" + asset);
            FrameLayout videoBox = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (width * VIDEO_RATIO), MeasureSpec.EXACTLY));
                }
            };
            videoBox.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            videoBox.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(11));
                }
            });
            videoBox.setClipToOutline(true);
            videoBox.addView(video, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            addView(videoBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout nameRow = new LinearLayout(context);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            radio = new RadioButton(context);
            radio.setSize(dp(20));
            radio.setColor(getThemedColor(Theme.key_radioBackground), getThemedColor(Theme.key_dialogRadioBackgroundChecked));
            if (locked) {
                ImageView lock = new ImageView(context);
                lock.setImageResource(R.drawable.msg_premium_lock2);
                lock.setColorFilter(getThemedColor(Theme.key_dialogTextGray3));
                nameRow.addView(lock, LayoutHelper.createLinear(22, 22, 0, 0, 8, 0));
            } else {
                nameRow.addView(radio, LayoutHelper.createLinear(22, 22, 0, 0, 8, 0));
            }
            TextView nameView = new TextView(context);
            nameView.setText(DealsGramText.get(name));
            nameView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            nameView.setTypeface(AndroidUtilities.bold());
            nameRow.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 10, 4, 0));

            TextView infoView = new TextView(context);
            infoView.setText(DealsGramText.get(info));
            infoView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
            infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoView.setLineSpacing(dp(1), 1f);
            addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 0));
            if (locked) {
                TextView plan = new TextView(context);
                plan.setText(DealsGramText.get("WithSubscription"));
                plan.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
                plan.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                plan.setTypeface(AndroidUtilities.bold());
                addView(plan, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 6, 4, 0));
            }

            setSelected(selected);
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
            radio.setChecked(selected, isAttachedToWindow());
            frame.setColor(selected ? Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), 0.08f) : 0);
            frame.setStroke(dp(selected ? 2 : 1), selected ? getThemedColor(Theme.key_featuredStickers_addButton) : getThemedColor(Theme.key_divider));
        }
    }

    /**
     * A muted MP4 played in a loop while it is on screen: from the app's assets, or streamed from an https URL
     * (the feature videos of dealsgram.app in "Primi passi"). Also used by AutoDealsPlanSheet.
     */
    static class LoopingVideo extends TextureView implements TextureView.SurfaceTextureListener {
        private final String asset;
        private MediaPlayer player;
        private Surface surface;
        private boolean active = true;
        private boolean prepared;
        private boolean loadAllowed = true;
        private SurfaceTexture pendingTexture;

        LoopingVideo(Context context, String asset) {
            super(context);
            this.asset = asset;
            setSurfaceTextureListener(this);
        }

        /** A carousel downloads only the card in view and its neighbours; the others wait here. */
        void setLoadAllowed(boolean value) {
            loadAllowed = value;
            if (value && player == null && pendingTexture != null) {
                open(pendingTexture);
            }
        }

        /** Plays only while active: a carousel keeps one video running and pauses the others. */
        void setActive(boolean value) {
            active = value;
            if (player != null && prepared) {
                if (value && !player.isPlaying()) {
                    player.start();
                } else if (!value && player.isPlaying()) {
                    player.pause();
                }
            }
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            pendingTexture = texture;
            if (loadAllowed) {
                open(texture);
            }
        }

        private void open(SurfaceTexture texture) {
            release();
            try {
                surface = new Surface(texture);
                player = new MediaPlayer();
                if (asset.startsWith("https://")) {
                    player.setDataSource(asset);
                } else {
                    try (AssetFileDescriptor file = getContext().getAssets().openFd(asset)) {
                        player.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                    }
                }
                player.setSurface(surface);
                player.setLooping(true);
                player.setVolume(0f, 0f);
                player.setOnPreparedListener(mp -> {
                    prepared = true;
                    if (active) {
                        mp.start();
                    } else {
                        // Paused cards of a carousel: draw the first frame instead of an empty box.
                        mp.seekTo(1);
                    }
                });
                player.prepareAsync();
            } catch (Exception e) {
                FileLog.e(e);
                release();
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            pendingTexture = null;
            release();
            return true;
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

        void release() {
            prepared = false;
            if (player != null) {
                player.release();
                player = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }
    }
}
