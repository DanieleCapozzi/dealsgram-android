package org.telegram.dealsgram;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * Impostazioni → Impostazioni DealsGram: how the app behaves, one section per area, built like Telegram's own
 * settings pages (UniversalRecyclerView). New options go here, not as more rows in Telegram's settings.
 */
public class DealsGramSettingsActivity extends BaseFragment {

    private static final int LINK_MODE = 1;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(DealsGramText.get("DealsGramSettingsTitle"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(DealsGramText.get("SettingsSectionPublishing")));
        items.add(UItem.asButton(LINK_MODE, R.drawable.msg_link, DealsGramText.get("LinkModeTitle"),
            DealsGramText.get(AutoDealsDraft.fieldMode() ? "LinkModeFieldShort" : "LinkModeSheetShort")));
        items.add(UItem.asShadow(DealsGramText.get("LinkModeFooter")));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == LINK_MODE) {
            AutoDealsLinkModeSheet.show(getContext(), getResourceProvider(), () -> listView.adapter.update(true));
        }
    }
}
