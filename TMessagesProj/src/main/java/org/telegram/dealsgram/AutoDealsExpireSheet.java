package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Offerta terminata" on a published post, as the bot and the mini-app do. A single post is ended with
 * expire_post. In a multi post the user picks which deals ended: each gets its own expired_at in the post's
 * offers (update_post), which the channel's formats and image formats show as ended, and AutoDeals edits
 * the message in the channel.
 */
final class AutoDealsExpireSheet {

    private static final AutoDealsApi.Origin MESSAGE = new AutoDealsApi.Origin("message", "message_menu");

    private interface Task {
        /** Runs off the main thread; returns the text of the success bulletin. */
        String run(AutoDealsApi api) throws Exception;
    }

    private AutoDealsExpireSheet() {
    }

    static void start(BaseFragment fragment, long channelId, int messageId) {
        int account = UserConfig.selectedAccount;
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject params = null;
            JSONObject editor = null;
            String error = null;
            try {
                AutoDealsApi api = AutoDealsApi.getInstance(account);
                JSONObject post = AutoDealsPublisher.findPublishedPost(api, channelId, messageId);
                if (post == null) {
                    throw new AutoDealsApi.AutoDealsException("post_not_found", DealsGramText.get("NotAutoDealsPost"));
                }
                params = new JSONObject();
                params.put("channel_id", channelId);
                params.put("type", post.optString("type"));
                params.put("ref_id", post.optLong("ref_id"));
                Object detail = api.call("get_post", params, MESSAGE);
                editor = detail instanceof JSONObject ? ((JSONObject) detail).optJSONObject("editor") : null;
                if (editor == null) {
                    throw new AutoDealsApi.AutoDealsException("post_not_found", DealsGramText.get("NotAutoDealsPost"));
                }
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            } catch (Exception e) {
                FileLog.e(e);
                error = DealsGramText.get("SomethingWrong");
            }
            JSONObject postParams = params;
            JSONObject postEditor = editor;
            String errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (errorText != null) {
                    BulletinFactory.of(fragment).createErrorBulletin(errorText).show();
                } else if ("multi".equals(postEditor.optString("kind"))) {
                    showOffers(fragment, account, postParams, postEditor.optJSONArray("offers"));
                } else if (!postEditor.isNull("expired_at")) {
                    BulletinFactory.of(fragment).createSuccessBulletin(DealsGramText.get("AlreadyExpired")).show();
                } else {
                    confirmSingle(fragment, account, postParams);
                }
            });
        });
    }

    private static void confirmSingle(BaseFragment fragment, int account, JSONObject params) {
        AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(DealsGramText.get("DealExpired"))
            .setMessage(DealsGramText.get("ExpireConfirm"))
            .setPositiveButton(DealsGramText.get("Expire"), (d, which) -> run(fragment, account, api -> {
                Object result = api.call("expire_post", params, MESSAGE);
                return syncText(result, DealsGramText.get("MarkedExpired"));
            }))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        fragment.showDialog(dialog);
        dialog.redPositive();
    }

    /** The deals of a multi post with a ring each; those already ended are ticked and cannot be changed. */
    private static void showOffers(BaseFragment fragment, int account, JSONObject params, JSONArray offers) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(DealsGramText.get("ExpireMultiTitle"));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 0));
        TextView hint = new TextView(context);
        hint.setText(DealsGramText.get("ExpireMultiHint"));
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setLineSpacing(dp(2), 1f);
        content.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 8));

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        List<Integer> chosen = new ArrayList<>();
        Runnable updateButton = () -> {
            button.setText(DealsGramText.get("Expire"), false);
            button.setCount(chosen.size(), true);
            button.setEnabled(!chosen.isEmpty());
        };

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int count = offers != null ? offers.length() : 0;
        for (int i = 0; i < count; i++) {
            JSONObject offer = offers.optJSONObject(i);
            if (offer == null) {
                continue;
            }
            JSONObject data = offer.optJSONObject("post_data");
            int index = offer.optInt("index", i);
            boolean ended = data != null && isEnded(data.optString("expired_at", null));
            list.addView(offerRow(context, resourcesProvider, data, ended, on -> {
                if (on) {
                    chosen.add(index);
                } else {
                    chosen.remove(Integer.valueOf(index));
                }
                updateButton.run();
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        updateButton.run();
        button.setOnClickListener(v -> {
            if (chosen.isEmpty()) {
                return;
            }
            sheet.dismiss();
            List<Integer> indexes = new ArrayList<>(chosen);
            run(fragment, account, api -> {
                // Server time (Europe/Rome), a minute back so the formats see it as already past.
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
                String now = format.format(new Date(System.currentTimeMillis() - 60_000L));
                JSONObject patches = new JSONObject();
                for (int index : indexes) {
                    patches.put(String.valueOf(index), new JSONObject().put("custom_data", new JSONObject().put("expired_at", now)));
                }
                JSONObject update = new JSONObject(params.toString());
                update.put("offers", patches);
                Object result = api.call("update_post", update, MESSAGE);
                return syncText(result, DealsGramText.plural("MarkedExpiredMany", indexes.size()));
            });
        });
        content.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 12));

        sheet.setCustomView(content);
        sheet.show();
    }

    private interface Toggle {
        void changed(boolean on);
    }

    private static LinearLayout offerRow(Context context, Theme.ResourcesProvider resourcesProvider, JSONObject data, boolean ended, Toggle toggle) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22), dp(10), dp(18), dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        String title = data != null ? data.optString("title", "") : "";
        name.setText(TextUtils.isEmpty(title) ? DealsGramText.get("DealExpired") : title);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        if (ended) {
            TextView state = new TextView(context);
            state.setText(DealsGramText.get("AlreadyExpiredShort"));
            state.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
            state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            texts.addView(state, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        CheckBox2 check = new CheckBox2(context, 22, resourcesProvider);
        // A grey ring when off, Telegram's blue check when on, as in the channel lists.
        check.setColor(Theme.key_checkbox, Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_checkboxCheck);
        check.setDrawBackgroundAsArc(10);
        check.setDrawUnchecked(true);
        check.setChecked(ended, false);
        row.addView(check, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        if (ended) {
            row.setAlpha(0.6f);
        } else {
            row.setOnClickListener(v -> {
                check.setChecked(!check.isChecked(), true);
                toggle.changed(check.isChecked());
            });
        }
        return row;
    }

    /** An expired_at already past ("Y-m-d H:i:s", server time); a lightning deal's future end does not count. */
    private static boolean isEnded(String expiredAt) {
        if (TextUtils.isEmpty(expiredAt) || "null".equals(expiredAt)) {
            return false;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
        try {
            Date date = format.parse(expiredAt);
            return date != null && date.getTime() <= System.currentTimeMillis();
        } catch (ParseException e) {
            return false;
        }
    }

    /** "message is not modified": the channel format shows nothing for an ended deal, which is fine. */
    private static String syncText(Object result, String done) {
        JSONObject sync = result instanceof JSONObject ? ((JSONObject) result).optJSONObject("telegram_sync") : null;
        String syncError = sync != null && !sync.optBoolean("ok", true) ? sync.optString("message") : null;
        if (syncError != null && !syncError.contains("not modified")) {
            return DealsGramText.format("ExpiredNotSynced", syncError);
        }
        return done;
    }

    private static void run(BaseFragment fragment, int account, Task task) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            String done = null;
            String error = null;
            try {
                done = task.run(AutoDealsApi.getInstance(account));
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            } catch (Exception e) {
                FileLog.e(e);
                error = DealsGramText.get("SomethingWrong");
            }
            String successText = done;
            String errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (errorText != null) {
                    BulletinFactory.of(fragment).createErrorBulletin(errorText).show();
                } else {
                    BulletinFactory.of(fragment).createSuccessBulletin(successText).show();
                }
            });
        });
    }
}
