package net.typeblog.socks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;
import net.typeblog.socks.util.Utility;
import static net.typeblog.socks.BuildConfig.DEBUG;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = BootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Profile p = new ProfileManager(context).getDefault();

            if (p.autoConnect() && VpnService.prepare(context) == null) {
                Utility.startVpn(context, p);
            }
        }
    }
}
