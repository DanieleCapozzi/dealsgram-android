package org.telegram.dealsgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.List;
import java.util.Locale;

/** Search filters with chip controls; changes apply when the user confirms. */
public class AutoDealsFiltersSheet extends BottomSheet {

    private ScrollView scroll;

    /** DealsGram: a swipe down closes the sheet only from the top of the list; elsewhere it scrolls the list. */
    @Override
    protected boolean canDismissWithSwipe() {
        return scroll == null || !scroll.canScrollVertically(-1);
    }

    private final AutoDealsSearchFilters filters;
    private final List<String> sourceKeys;
    /** category_facets of the first page: key (null = uncategorised), label, count. */
    private final List<JSONObject> facets;
    private final Utilities.Callback<AutoDealsSearchFilters> onApply;
    private final LinearLayout column;
    private EditText minPrice;
    private EditText maxPrice;

    public AutoDealsFiltersSheet(Context context, Theme.ResourcesProvider resourcesProvider, AutoDealsSearchFilters current,
                                 List<String> sourceKeys, List<JSONObject> facets, Utilities.Callback<AutoDealsSearchFilters> onApply) {
        super(context, true, resourcesProvider);
        this.filters = current.copy();
        this.sourceKeys = sourceKeys;
        this.facets = facets;
        this.onApply = onApply;

        int gray = getThemedColor(Theme.key_windowBackgroundGray);
        setBackgroundColor(gray);
        fixNavigationBar(gray);
        setApplyBottomPadding(false);

        FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = (int) ((AndroidUtilities.displaySize.y - AndroidUtilities.statusBarHeight) * 0.86f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        scroll = new ScrollView(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(6 + 48 + 12 + 8));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FrameLayout header = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setText(DealsGramText.get("Filters"));
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

        LinearLayout buttons = new LinearLayout(context);
        buttons.setPadding(dp(12), dp(6), dp(12), dp(12));
        buttons.setBackgroundColor(gray);
        ButtonWithCounterView reset = new ButtonWithCounterView(context, false, resourcesProvider).setRound();
        reset.setText(DealsGramText.get("Reset"), false);
        reset.setOnClickListener(v -> {
            filters.reset();
            build();
        });
        buttons.addView(reset, LayoutHelper.createLinear(0, 48, 1f, Gravity.FILL, 0, 0, 10, 0));
        ButtonWithCounterView apply = new ButtonWithCounterView(context, resourcesProvider).setRound();
        apply.setText(DealsGramText.get("ShowDeals"), false);
        apply.setOnClickListener(v -> {
            readPrices();
            dismiss();
            onApply.run(filters);
        });
        buttons.addView(apply, LayoutHelper.createLinear(0, 48, 1.4f, Gravity.FILL));
        root.addView(buttons, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        setCustomView(root);
        build();
    }

    /** Rebuilds every card from {@link #filters}; also after "Azzera". */
    private void build() {
        while (column.getChildCount() > 1) {
            column.removeViewAt(1);
        }
        Context context = getContext();

        if (sourceKeys.size() > 1) {
            WrapLayout chips = section(DealsGramText.get("Store"));
            for (String key : sourceKeys) {
                chips.addView(toggleChip(AutoDealsSearchFilters.sourceLabel(key), filters.sources, key));
            }
        }

        WrapLayout deals = section(DealsGramText.get("DealType"));
        for (String type : AutoDealsSearchFilters.DEAL_TYPES.keySet()) {
            deals.addView(toggleChip(AutoDealsSearchFilters.dealTypeLabel(type), filters.dealTypes, type));
        }

        LinearLayout prices = card(DealsGramText.get("Price"), null);
        LinearLayout priceRow = new LinearLayout(context);
        minPrice = priceInput(DealsGramText.get("Min"), filters.minPrice);
        maxPrice = priceInput(DealsGramText.get("Max"), filters.maxPrice);
        priceRow.addView(minPrice, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 10, 0));
        priceRow.addView(maxPrice, LayoutHelper.createLinear(0, 44, 1f));
        prices.addView(priceRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 14));

        options(DealsGramText.get("MinDiscount"), AutoDealsSearchFilters.DISCOUNTS, filters.minDiscount, v -> String.format(DealsGramText.locale(), "%d%%", v.intValue()), v -> filters.minDiscount = v);
        options(DealsGramText.get("PriceHistory"), AutoDealsSearchFilters.LOWEST, filters.maxAboveLowestPerc,
            v -> v == 0 ? DealsGramText.get("AtAllTimeLow") : DealsGramText.format("WithinPercent", v.intValue()), v -> filters.maxAboveLowestPerc = v);
        options(DealsGramText.get("UpdatedWithin"), AutoDealsSearchFilters.UPDATED_HOURS, filters.updatedWithinHours,
            v -> v >= 24 && v % 24 == 0 ? DealsGramText.format("DaysShort", (int) (v / 24)) : DealsGramText.format("HoursShort", v.intValue()), v -> filters.updatedWithinHours = v);
        options(DealsGramText.get("MinRating"), AutoDealsSearchFilters.RATINGS, filters.minRating,
            v -> (v == Math.rint(v) ? String.valueOf(v.intValue()) : String.format(DealsGramText.locale(), "%.1f", v)) + " ★", v -> filters.minRating = v);
        options(DealsGramText.get("MinReviews"), AutoDealsSearchFilters.REVIEWS, filters.minReviews,
            v -> String.format(DealsGramText.locale(), "%,d", v.longValue()), v -> filters.minReviews = v);
        options(DealsGramText.get("SalesRank"), AutoDealsSearchFilters.SALES_RANKS, filters.maxSalesRank,
            v -> DealsGramText.format("TopRank", v.longValue()), v -> filters.maxSalesRank = v);

        LinearLayout switches = card(null, null);
        switches.addView(switchRow(DealsGramText.get("OnlyCoupon"), filters.onlyCoupon, true, v -> filters.onlyCoupon = v));
        switches.addView(switchRow(DealsGramText.get("OnlyStrikethrough"), filters.onlyDiscounted, true, v -> filters.onlyDiscounted = v));
        switches.addView(switchRow(DealsGramText.get("HideExisting"), filters.hideExisting, false, v -> filters.hideExisting = v));

        // Last: the list is long, the other filters stay within reach.
        if (!facets.isEmpty()) {
            LinearLayout card = card(DealsGramText.get("Categories"), DealsGramText.get("CategoriesHint"));
            for (JSONObject facet : facets) {
                String key = facet.isNull("key") ? AutoDealsSearchFilters.UNCATEGORIZED : facet.optString("key");
                String label = facet.isNull("label") ? DealsGramText.get("NoCategory") : facet.optString("label");
                card.addView(categoryRow(key, label, facet.optInt("count")));
            }
        }
    }

    // --- Cards -------------------------------------------------------------------

    private LinearLayout card(String title, String hint) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        if (title != null) {
            TextView header = new TextView(context);
            header.setText(title);
            header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            header.setTypeface(AndroidUtilities.bold());
            card.addView(header, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, hint != null ? 2 : 10));
        }
        if (hint != null) {
            TextView text = new TextView(context);
            text.setText(hint);
            text.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));
        }
        column.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 10));
        return card;
    }

    private WrapLayout section(String title) {
        LinearLayout card = card(title, null);
        WrapLayout chips = new WrapLayout(getContext());
        card.addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 12));
        return chips;
    }

    /** One choice among values; null is "Qualsiasi". */
    private void options(String title, Double[] values, Double current, Utilities.CallbackReturn<Double, String> label, Utilities.Callback<Double> onPick) {
        WrapLayout chips = section(title);
        for (Double value : values) {
            boolean on = value == null ? current == null : value.equals(current);
            TextView chip = chip(value == null ? DealsGramText.get("Any") : label.run(value), on);
            chip.setOnClickListener(v -> {
                readPrices();
                onPick.run(value);
                build();
            });
            chips.addView(chip);
        }
    }

    private TextView toggleChip(String text, List<String> list, String key) {
        TextView chip = chip(text, list.contains(key));
        chip.setOnClickListener(v -> {
            readPrices();
            if (!list.remove(key)) {
                list.add(key);
            }
            build();
        });
        return chip;
    }

    private TextView chip(String text, boolean on) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(on ? getThemedColor(Theme.key_featuredStickers_buttonText) : getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        chip.setBackground(Theme.createRoundRectDrawable(dp(16), on ? accent : getThemedColor(Theme.key_windowBackgroundGray)));
        return chip;
    }

    /** Category with its count: none → shown → excluded → none, like the dashboard's two buttons. */
    private View categoryRow(String key, String label, int count) {
        Context context = getContext();
        boolean included = filters.categories.contains(key);
        boolean excluded = filters.excludeCategories.contains(key);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));
        TextView name = new TextView(context);
        name.setText(excluded ? DealsGramText.format("CategoryExcluded", label) : label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTextColor(getThemedColor(excluded ? Theme.key_text_RedRegular
            : included ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteBlackText));
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        TextView number = new TextView(context);
        number.setText(String.format(DealsGramText.locale(), "%,d", count));
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        number.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(number);
        ImageView mark = new ImageView(context);
        mark.setScaleType(ImageView.ScaleType.CENTER);
        if (included || excluded) {
            mark.setImageResource(included ? R.drawable.msg_check_s : R.drawable.msg_close);
            mark.setColorFilter(getThemedColor(included ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_text_RedRegular));
        }
        row.addView(mark, LayoutHelper.createLinear(28, 28, 8, 0, 0, 0));
        row.setOnClickListener(v -> {
            readPrices();
            if (included) {
                filters.categories.remove(key);
                filters.excludeCategories.add(key);
            } else if (excluded) {
                filters.excludeCategories.remove(key);
            } else {
                filters.categories.add(key);
            }
            build();
        });
        return row;
    }

    private EditText priceInput(String hint, Double value) {
        EditText input = new EditText(getContext());
        input.setHint(hint + " €");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setSingleLine(true);
        input.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_windowBackgroundGray)));
        if (value != null) {
            input.setText(value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value));
        }
        return input;
    }

    private void readPrices() {
        filters.minPrice = number(minPrice);
        filters.maxPrice = number(maxPrice);
    }

    private static Double number(EditText input) {
        if (input == null || TextUtils.isEmpty(input.getText())) {
            return null;
        }
        try {
            double value = Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private View switchRow(String text, boolean checked, boolean divider, Utilities.Callback<Boolean> onChange) {
        TextCheckCell cell = new TextCheckCell(getContext(), resourcesProvider);
        cell.setTextAndCheck(text, checked, divider);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> {
            cell.setChecked(!cell.isChecked());
            onChange.run(cell.isChecked());
        });
        return cell;
    }

    /** Chips that wrap onto new lines. */
    private static class WrapLayout extends ViewGroup {
        WrapLayout(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int x = 0;
            int y = 0;
            int lineHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    x = 0;
                    y += lineHeight + dp(8);
                    lineHeight = 0;
                }
                x += child.getMeasuredWidth() + dp(8);
                lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + lineHeight);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int x = 0;
            int y = 0;
            int lineHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    x = 0;
                    y += lineHeight + dp(8);
                    lineHeight = 0;
                }
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + dp(8);
                lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
            }
        }
    }
}
