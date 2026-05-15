package net.typeblog.socks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;
import net.typeblog.socks.util.Utility;

public class BootReceiver extends BroadcastReceiver {
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
