package org.telegram.dealsgram;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.telegram.ui.LaunchActivity;

/**
 * "Pubblica offerta" in Android's share menu: Amazon, AliExpress or any app shares a link, and DealsGram
 * opens with the publishing sheet. It has no UI of its own; it hands the link to LaunchActivity.
 */
public class ShareToPublishActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent source = getIntent();
        String link = null;
        if (source != null && Intent.ACTION_SEND.equals(source.getAction())) {
            link = AutoDealsPublisher.findLink(source.getCharSequenceExtra(Intent.EXTRA_TEXT));
            if (link == null) {
                link = AutoDealsPublisher.findLink(source.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
            }
        }
        if (link == null) {
            Toast.makeText(this, DealsGramText.get("NoLinkToPublish"), Toast.LENGTH_SHORT).show();
        } else {
            Intent open = new Intent(this, LaunchActivity.class);
            open.setAction(AutoDealsPublisher.ACTION_PUBLISH);
            open.putExtra(AutoDealsPublisher.EXTRA_LINK, link);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
        }
        finish();
    }
}
