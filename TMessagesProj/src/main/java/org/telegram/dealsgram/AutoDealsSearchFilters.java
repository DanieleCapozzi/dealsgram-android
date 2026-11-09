package org.telegram.dealsgram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filters and sorts of the dashboard's "Cerca offerte" (autodeals-desktop-svelte/src/lib/offer-filters.ts):
 * {@link #putParams} sends exactly the parameters of filterParams(), and search_autoposting_v2_offers
 * applies them on the server (filters_applied), so the app never filters on its own.
 */
public class AutoDealsSearchFilters {

    /** Sort keys accepted by search_autoposting_v2_offers, with the DealsGramText key of the dashboard's labels. */
    public static final Map<String, String> SORTS = new LinkedHashMap<>();
    static {
        SORTS.put("discount_perc", "SortDiscount");
        SORTS.put("lowest_gap", "SortLowestGap");
        SORTS.put("updated_at", "SortUpdated");
        SORTS.put("price_asc", "SortPriceAsc");
        SORTS.put("price_desc", "SortPriceDesc");
        SORTS.put("reviews_desc", "SortReviews");
        SORTS.put("rating_desc", "SortRating");
        SORTS.put("sales_rank_asc", "SortSales");
    }

    /** Deal types in the dashboard's order (DEAL_TYPES in lib/types), with the DealsGramText key of their label. */
    public static final Map<String, String> DEAL_TYPES = new LinkedHashMap<>();
    static {
        DEAL_TYPES.put("lightning", "DealLightning");
        DEAL_TYPES.put("normal", "DealStandard");
        DEAL_TYPES.put("coupon", "DealCoupon");
        DEAL_TYPES.put("warehouse", "DealWarehouse");
        DEAL_TYPES.put("error", "DealError");
        DEAL_TYPES.put("lowest", "DealLowest");
    }

    public static final Map<String, String> STORES = new LinkedHashMap<>();
    static {
        STORES.put("AZ", "Amazon");
        STORES.put("AE", "AliExpress");
        STORES.put("COMET", "Comet");
        STORES.put("OTTO", "Otto");
        STORES.put("UNIEURO", "Unieuro");
        STORES.put("FUXTEC", "FUXTEC");
        STORES.put("MEDIAWORLD", "MediaWorld");
        STORES.put("TOYSCENTER", "Toys Center");
    }

    /** Offers without a macro category in the facets (key null); never sent to the API. */
    public static final String UNCATEGORIZED = "__uncategorized__";

    public static final Double[] DISCOUNTS = {null, 10d, 20d, 30d, 50d, 70d};
    public static final Double[] LOWEST = {null, 0d, 5d, 10d, 25d};
    public static final Double[] UPDATED_HOURS = {null, 1d, 6d, 24d, 168d};
    public static final Double[] RATINGS = {null, 3d, 4d, 4.5d};
    public static final Double[] REVIEWS = {null, 10d, 100d, 1000d};
    public static final Double[] SALES_RANKS = {null, 1000d, 10000d, 100000d};

    public String sort = "updated_at";
    /** "STORE|NATION"; empty = every active source. */
    public final List<String> sources = new ArrayList<>();
    public final List<String> categories = new ArrayList<>();
    /** Excluded categories win over included ones, as on the server. */
    public final List<String> excludeCategories = new ArrayList<>();
    public final List<String> dealTypes = new ArrayList<>();
    public Double minPrice;
    public Double maxPrice;
    public Double minDiscount;
    /** Highest distance from the historical low, in %; 0 = at the low. */
    public Double maxAboveLowestPerc;
    public Double updatedWithinHours;
    public Double minRating;
    public Double minReviews;
    public Double maxSalesRank;
    public boolean onlyCoupon;
    public boolean onlyDiscounted;
    /** Hides products published recently, scheduled or in the curated queue. */
    public boolean hideExisting;

    public AutoDealsSearchFilters copy() {
        AutoDealsSearchFilters c = new AutoDealsSearchFilters();
        c.sort = sort;
        c.sources.addAll(sources);
        c.categories.addAll(categories);
        c.excludeCategories.addAll(excludeCategories);
        c.dealTypes.addAll(dealTypes);
        c.minPrice = minPrice;
        c.maxPrice = maxPrice;
        c.minDiscount = minDiscount;
        c.maxAboveLowestPerc = maxAboveLowestPerc;
        c.updatedWithinHours = updatedWithinHours;
        c.minRating = minRating;
        c.minReviews = minReviews;
        c.maxSalesRank = maxSalesRank;
        c.onlyCoupon = onlyCoupon;
        c.onlyDiscounted = onlyDiscounted;
        c.hideExisting = hideExisting;
        return c;
    }

    /** Everything except the sort goes back to its default. */
    public void reset() {
        sources.clear();
        categories.clear();
        excludeCategories.clear();
        dealTypes.clear();
        minPrice = maxPrice = minDiscount = maxAboveLowestPerc = updatedWithinHours = null;
        minRating = minReviews = maxSalesRank = null;
        onlyCoupon = onlyDiscounted = hideExisting = false;
    }

    /** Same count as activeFilterCount(): price range counts once. */
    public int count() {
        int n = 0;
        n += sources.isEmpty() ? 0 : 1;
        n += categories.isEmpty() ? 0 : 1;
        n += excludeCategories.isEmpty() ? 0 : 1;
        n += dealTypes.isEmpty() ? 0 : 1;
        n += minPrice != null || maxPrice != null ? 1 : 0;
        for (Double value : new Double[]{minDiscount, maxAboveLowestPerc, updatedWithinHours, minRating, minReviews, maxSalesRank}) {
            n += value != null ? 1 : 0;
        }
        n += onlyCoupon ? 1 : 0;
        n += onlyDiscounted ? 1 : 0;
        n += hideExisting ? 1 : 0;
        return n;
    }

    /** filterParams(): missing values are not sent, hide_existing_products always is. */
    public void putParams(JSONObject params) throws Exception {
        params.put("sort", sort);
        params.put("hide_existing_products", hideExisting);
        if (!sources.isEmpty()) {
            JSONArray list = new JSONArray();
            for (String key : sources) {
                String[] parts = key.split("\\|", 2);
                JSONObject source = new JSONObject();
                source.put("store", parts[0]);
                source.put("nation", parts.length > 1 ? parts[1] : "");
                list.put(source);
            }
            params.put("sources", list);
        }
        JSONArray included = realCategories(categories);
        if (included.length() > 0) {
            params.put("categories", included);
        }
        JSONArray excluded = realCategories(excludeCategories);
        if (excluded.length() > 0) {
            params.put("exclude_categories", excluded);
        }
        if (!dealTypes.isEmpty()) {
            params.put("deal_types", new JSONArray(dealTypes));
        }
        putNumber(params, "min_price", minPrice);
        putNumber(params, "max_price", maxPrice);
        putNumber(params, "min_discount", minDiscount);
        putNumber(params, "max_above_lowest_perc", maxAboveLowestPerc);
        putNumber(params, "updated_within_hours", updatedWithinHours);
        putNumber(params, "min_rating", minRating);
        putNumber(params, "min_reviews", minReviews);
        putNumber(params, "max_sales_rank", maxSalesRank);
        if (onlyCoupon) {
            params.put("has_coupon", true);
        }
        if (onlyDiscounted) {
            params.put("discounted_only", true);
        }
    }

    private static JSONArray realCategories(List<String> keys) {
        JSONArray result = new JSONArray();
        for (String key : keys) {
            if (!UNCATEGORIZED.equals(key)) {
                result.put(key);
            }
        }
        return result;
    }

    private static void putNumber(JSONObject params, String key, Double value) throws Exception {
        if (value != null) {
            if (value == Math.rint(value)) {
                params.put(key, value.longValue());
            } else {
                params.put(key, value);
            }
        }
    }

    public static String sortLabel(String sort) {
        return SORTS.containsKey(sort) ? DealsGramText.get(SORTS.get(sort)) : sort;
    }

    public static String dealTypeLabel(String type) {
        return DEAL_TYPES.containsKey(type) ? DealsGramText.get(DEAL_TYPES.get(type)) : null;
    }

    public static String sourceLabel(String key) {
        String[] parts = key.split("\\|", 2);
        String store = STORES.containsKey(parts[0]) ? STORES.get(parts[0]) : parts[0];
        return parts.length > 1 && !parts[1].isEmpty() ? store + " " + parts[1] : store;
    }
}
