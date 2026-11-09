package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Price history of an offer (get_offer_price_history), as the dashboard's PriceHistorySheet:
 * 30/90/180/365 days, a step chart with the unavailable periods and the period low, and the four numbers.
 */
public class AutoDealsPriceHistorySheet extends BottomSheet {

    private static final int[] RANGES = {30, 90, 180, 365};

    private final int account;
    private final long channelId;
    private final JSONObject offer;
    private int range = 90;
    private int loadToken;

    private final LinearLayout rangeRow;
    private final FrameLayout chartBox;
    private final ChartView chart;
    private final RadialProgressView progress;
    private final TextView chartMessage;
    private final TextView[] statValues = new TextView[4];

    public AutoDealsPriceHistorySheet(Context context, Theme.ResourcesProvider resourcesProvider, long channelId, JSONObject offer) {
        super(context, true, resourcesProvider);
        this.account = UserConfig.selectedAccount;
        this.channelId = channelId;
        this.offer = offer;

        int gray = getThemedColor(Theme.key_windowBackgroundGray);
        setBackgroundColor(gray);
        fixNavigationBar(gray);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(12));

        FrameLayout header = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setText(DealsGramText.get("PriceHistory"));
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

        // Product.
        LinearLayout product = new LinearLayout(context);
        product.setGravity(Gravity.CENTER_VERTICAL);
        product.setPadding(dp(12), dp(12), dp(16), dp(12));
        product.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        BackupImageView image = new BackupImageView(context);
        image.setRoundRadius(dp(10));
        String photo = offer.optString("image", "");
        if (photo.startsWith("http")) {
            image.setImage(photo, "112_112", null);
        }
        product.addView(image, LayoutHelper.createLinear(52, 52, 0, 0, 12, 0));
        TextView name = new TextView(context);
        name.setText(android.text.Html.fromHtml(offer.optString("title", ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString());
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        product.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        column.addView(product, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));

        rangeRow = new LinearLayout(context);
        rangeRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        rangeRow.setBackground(Theme.createRoundRectDrawable(dp(22), getThemedColor(Theme.key_windowBackgroundWhite)));
        column.addView(rangeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 0, 12, 10));

        chartBox = new FrameLayout(context);
        chartBox.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        chart = new ChartView(context);
        chartBox.addView(chart, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 8, 12, 12, 8));
        progress = new RadialProgressView(context, resourcesProvider);
        progress.setSize(dp(32));
        chartBox.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
        chartMessage = new TextView(context);
        chartMessage.setGravity(Gravity.CENTER);
        chartMessage.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chartMessage.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        chartBox.addView(chartMessage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));
        column.addView(chartBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 240, 12, 0, 12, 10));

        // Current, period low, historical low, average.
        LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.setPadding(dp(16), dp(8), dp(16), dp(8));
        stats.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        String[] labels = {DealsGramText.get("CurrentPrice"), DealsGramText.get("PeriodLow"), DealsGramText.get("AllTimeLow"), DealsGramText.get("Average")};
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(context);
            for (int c = 0; c < 2; c++) {
                int index = r * 2 + c;
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                TextView label = new TextView(context);
                label.setText(labels[index]);
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                cell.addView(label);
                TextView value = new TextView(context);
                value.setText("—");
                value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                value.setTypeface(AndroidUtilities.bold());
                value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                cell.addView(value);
                statValues[index] = value;
                line.addView(cell, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 6, 0, 6));
            }
            stats.addView(line);
        }
        column.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        // Values already known from the row, until the history arrives.
        double current = offer.optDouble("deal_price", Double.NaN);
        double lowest = offer.optDouble("historical_lowest_price", Double.NaN);
        statValues[0].setText(price(current, "EUR"));
        statValues[2].setText(price(lowest, "EUR"));

        setCustomView(column);
        buildRanges();
        load();
    }

    private void buildRanges() {
        rangeRow.removeAllViews();
        for (int days : RANGES) {
            TextView tab = new TextView(getContext());
            tab.setText(DealsGramText.format("DaysCount", days));
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            boolean on = days == range;
            tab.setTypeface(on ? AndroidUtilities.bold() : null);
            tab.setTextColor(getThemedColor(on ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
            tab.setBackground(on ? Theme.createRoundRectDrawable(dp(18), getThemedColor(Theme.key_featuredStickers_addButton)) : null);
            tab.setOnClickListener(v -> {
                if (range != days) {
                    range = days;
                    buildRanges();
                    load();
                }
            });
            rangeRow.addView(tab, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
    }

    private void load() {
        int token = ++loadToken;
        progress.setVisibility(View.VISIBLE);
        chartMessage.setVisibility(View.GONE);
        chart.setData(null, null, range);
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject result = null;
            String error = null;
            try {
                JSONObject params = new JSONObject();
                params.put("channel_id", channelId);
                params.put("offer_id", offer.optLong("offer_id"));
                params.put("range_days", range);
                Object data = AutoDealsApi.getInstance(account).call("get_offer_price_history", params, new AutoDealsApi.Origin("price_history", "chat_menu"));
                result = data instanceof JSONObject ? (JSONObject) data : null;
            } catch (AutoDealsApi.AutoDealsException e) {
                error = e.text;
            } catch (Exception e) {
                error = DealsGramText.get("HistoryLoadFailed");
            }
            JSONObject history = result;
            String errorText = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != loadToken) {
                    return;
                }
                progress.setVisibility(View.GONE);
                if (history == null) {
                    chartMessage.setText(errorText != null ? errorText : DealsGramText.get("HistoryLoadFailed"));
                    chartMessage.setVisibility(View.VISIBLE);
                    return;
                }
                String currency = history.optString("currency", "EUR");
                JSONObject stats = history.optJSONObject("stats");
                if (stats != null) {
                    double current = stats.optDouble("current_price", Double.NaN);
                    double historical = stats.optDouble("historical_lowest_price", Double.NaN);
                    statValues[0].setText(price(current, currency));
                    statValues[1].setText(price(stats.optDouble("lowest_price", Double.NaN), currency));
                    statValues[2].setText(price(historical, currency));
                    statValues[2].setTextColor(getThemedColor(!Double.isNaN(current) && !Double.isNaN(historical) && current <= historical
                        ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_windowBackgroundWhiteBlackText));
                    statValues[3].setText(price(stats.optDouble("average_price", Double.NaN), currency));
                }
                if (!chart.setData(history.optJSONArray("points"), currency, range)) {
                    chartMessage.setText(DealsGramText.get("HistoryEmpty"));
                    chartMessage.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    static String price(double value, String currency) {
        if (Double.isNaN(value)) {
            return "—";
        }
        NumberFormat format = NumberFormat.getCurrencyInstance(DealsGramText.locale());
        try {
            format.setCurrency(Currency.getInstance(currency));
        } catch (Exception ignore) {
        }
        return format.format(value);
    }

    /** The step chart of price-history.ts (buildChart), with a touch readout. */
    private class ChartView extends View {

        private static final int LEFT = 52;
        private static final int BOTTOM = 22;
        private static final int TOP = 10;

        private final List<long[]> times = new ArrayList<>();
        private final List<Double> prices = new ArrayList<>();
        private String currency = "EUR";
        private int rangeDays = 90;
        private long start;
        private long end;
        private double yMin;
        private double yMax;
        private double step;
        private int touchIndex = -1;

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gap = new Paint();
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint bubbleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ChartView(Context context) {
            super(context);
            int accent = getThemedColor(Theme.key_featuredStickers_addButton);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(accent);
            area.setColor(Theme.multAlpha(accent, .12f));
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(1);
            grid.setColor(getThemedColor(Theme.key_divider));
            grid.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
            gap.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText), .12f));
            label.setTextSize(dp(11));
            label.setColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            bubbleText.setTextSize(dp(12));
            bubbleText.setColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            bubble.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        /** Returns false when no point has a price. */
        boolean setData(JSONArray points, String currency, int rangeDays) {
            times.clear();
            prices.clear();
            touchIndex = -1;
            this.currency = currency != null ? currency : "EUR";
            this.rangeDays = rangeDays;
            if (points == null) {
                invalidate();
                return false;
            }
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            List<Object[]> parsed = new ArrayList<>();
            for (int i = 0; i < points.length(); i++) {
                JSONObject point = points.optJSONObject(i);
                if (point == null) {
                    continue;
                }
                try {
                    long t = iso.parse(point.optString("observed_at")).getTime();
                    boolean available = point.optBoolean("available") && !point.isNull("deal_price");
                    parsed.add(new Object[]{t, available ? point.optDouble("deal_price") : null});
                } catch (Exception ignore) {
                }
            }
            java.util.Collections.sort(parsed, (a, b) -> Long.compare((long) a[0], (long) b[0]));
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            for (Object[] entry : parsed) {
                times.add(new long[]{(long) entry[0]});
                prices.add((Double) entry[1]);
                if (entry[1] != null) {
                    low = Math.min(low, (Double) entry[1]);
                    high = Math.max(high, (Double) entry[1]);
                }
            }
            if (low == Double.MAX_VALUE) {
                times.clear();
                prices.clear();
                invalidate();
                return false;
            }
            end = Math.max(System.currentTimeMillis(), times.get(times.size() - 1)[0]);
            start = Math.min(end - rangeDays * 86_400_000L, times.get(0)[0]);
            if (high - low < Math.max(1, high * 0.02)) {
                // Almost flat: a margin keeps the line off the edges.
                double pad = Math.max(1, high * 0.05);
                low -= pad;
                high += pad;
            }
            step = niceStep(high - low, 4);
            yMin = Math.max(0, Math.floor(low / step) * step);
            yMax = Math.ceil(high / step) * step;
            invalidate();
            return true;
        }

        private double niceStep(double span, int count) {
            double raw = span / Math.max(1, count);
            double power = Math.pow(10, Math.floor(Math.log10(raw)));
            double unit = raw / power;
            double nice = unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 2.5 ? 2.5 : unit <= 5 ? 5 : 10;
            return nice * power;
        }

        private float x(long t) {
            float plot = getWidth() - dp(LEFT);
            return dp(LEFT) + (float) ((t - start) / (double) Math.max(1, end - start)) * plot;
        }

        private float y(double value) {
            float plot = getHeight() - dp(TOP) - dp(BOTTOM);
            return dp(TOP) + (float) (1 - (value - yMin) / Math.max(1e-9, yMax - yMin)) * plot;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (times.isEmpty()) {
                return;
            }
            float baseline = getHeight() - dp(BOTTOM);
            NumberFormat axis = NumberFormat.getNumberInstance(DealsGramText.locale());
            axis.setMaximumFractionDigits(step < 1 ? 2 : 0);
            for (double v = yMin; v <= yMax + step / 2; v += step) {
                float yy = y(v);
                canvas.drawLine(dp(LEFT), yy, getWidth(), yy, grid);
                String text = axis.format(v);
                canvas.drawText(text, dp(LEFT) - dp(6) - label.measureText(text), yy + dp(4), label);
            }
            SimpleDateFormat day = new SimpleDateFormat(DealsGramText.get("DateDayMonth"), DealsGramText.locale());
            for (int i = 0; i <= 3; i++) {
                long t = start + (end - start) * i / 3;
                String text = day.format(new Date(t));
                float xx = x(t) - (i == 0 ? 0 : i == 3 ? label.measureText(text) : label.measureText(text) / 2);
                canvas.drawText(text, xx, getHeight() - dp(4), label);
            }

            // Unavailable periods, then the available segments as steps: each price holds until the next point.
            Float gapStart = null;
            path.reset();
            Path fill = new Path();
            boolean open = false;
            float segmentX = 0;
            float lastY = 0;
            int lowest = -1;
            for (int i = 0; i < times.size(); i++) {
                float xx = x(times.get(i)[0]);
                Double value = prices.get(i);
                if (value == null) {
                    if (open) {
                        closeSegment(fill, xx, lastY, segmentX, baseline);
                        open = false;
                    }
                    if (gapStart == null) {
                        gapStart = xx;
                    }
                    continue;
                }
                if (gapStart != null) {
                    canvas.drawRect(gapStart, dp(TOP), xx, baseline, gap);
                    gapStart = null;
                }
                float yy = y(value);
                if (!open) {
                    path.moveTo(xx, yy);
                    fill.moveTo(xx, yy);
                    segmentX = xx;
                    open = true;
                } else {
                    path.lineTo(xx, lastY);
                    fill.lineTo(xx, lastY);
                    path.lineTo(xx, yy);
                    fill.lineTo(xx, yy);
                }
                lastY = yy;
                if (lowest < 0 || value < prices.get(lowest)) {
                    lowest = i;
                }
            }
            float endX = x(end);
            if (open) {
                closeSegment(fill, endX, lastY, segmentX, baseline);
            } else if (gapStart != null) {
                canvas.drawRect(gapStart, dp(TOP), endX, baseline, gap);
            }
            canvas.drawPath(fill, area);
            canvas.drawPath(path, line);

            if (lowest >= 0) {
                dot.setColor(getThemedColor(Theme.key_windowBackgroundWhiteGreenText));
                canvas.drawCircle(x(times.get(lowest)[0]), y(prices.get(lowest)), dp(4), dot);
            }

            if (touchIndex >= 0 && prices.get(touchIndex) != null) {
                float xx = x(times.get(touchIndex)[0]);
                float yy = y(prices.get(touchIndex));
                canvas.drawLine(xx, dp(TOP), xx, baseline, grid);
                dot.setColor(line.getColor());
                canvas.drawCircle(xx, yy, dp(4), dot);
                String text = new SimpleDateFormat(DealsGramText.get("DateDayMonthYear"), DealsGramText.locale()).format(new Date(times.get(touchIndex)[0]))
                    + " · " + price(prices.get(touchIndex), currency);
                float w = bubbleText.measureText(text) + dp(16);
                float left = Math.max(dp(LEFT), Math.min(getWidth() - w, xx - w / 2));
                RectF rect = new RectF(left, 0, left + w, dp(24));
                canvas.drawRoundRect(rect, dp(8), dp(8), bubble);
                canvas.drawText(text, left + dp(8), dp(16), bubbleText);
            }
        }

        private void closeSegment(Path fill, float untilX, float lastY, float segmentX, float baseline) {
            path.lineTo(untilX, lastY);
            fill.lineTo(untilX, lastY);
            fill.lineTo(untilX, baseline);
            fill.lineTo(segmentX, baseline);
            fill.close();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (times.isEmpty()) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                touchIndex = -1;
                invalidate();
                return true;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            int best = -1;
            float bestDistance = Float.MAX_VALUE;
            for (int i = 0; i < times.size(); i++) {
                if (prices.get(i) == null) {
                    continue;
                }
                float distance = Math.abs(x(times.get(i)[0]) - event.getX());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            touchIndex = best;
            invalidate();
            return true;
        }
    }
}
