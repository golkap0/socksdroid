package net.typeblog.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static net.typeblog.socks.util.Constants.*;
import static net.typeblog.socks.BuildConfig.DEBUG;

public class SocksVpnService extends VpnService {
    class VpnBinder extends IVpnService.Stub {
        @Override
        public boolean isRunning() {
            return mRunning;
        }

        @Override
        public void stop() {
            stopMe();
        }
    }

    private static final String TAG = SocksVpnService.class.getSimpleName();

    private ParcelFileDescriptor mInterface;
    private boolean mRunning = false;
    private final IBinder mBinder = new VpnBinder();

    // FIX: Melacak proses native yang berjalan agar tidak jadi proses Zombie
    private final List<Process> mNativeDaemons = Collections.synchronizedList(new ArrayList<>());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (DEBUG) {
            Log.d(TAG, "starting");
        }

        if (intent == null) {
            // FIX: Gunakan NOT_STICKY agar OS tidak memaksakan hidup service dengan intent kosong
            return START_NOT_STICKY; 
        }

        if (mRunning) {
            return START_STICKY;
        }

        final String name = intent.getStringExtra(INTENT_NAME);
        final String server = intent.getStringExtra(INTENT_SERVER);
        final int port = intent.getIntExtra(INTENT_PORT, 1080);
        final String username = intent.getStringExtra(INTENT_USERNAME);
        final String passwd = intent.getStringExtra(INTENT_PASSWORD);
        final String route = intent.getStringExtra(INTENT_ROUTE);
        final String dns = intent.getStringExtra(INTENT_DNS);
        final int dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53);
        final boolean perApp = intent.getBooleanExtra(INTENT_PER_APP, false);
        final boolean appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false);
        final String[] appList = intent.getStringArrayExtra(INTENT_APP_LIST);
        final boolean ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false);
        final String udpgw = intent.getStringExtra(INTENT_UDP_GW);
        final String obfs = intent.getStringExtra(INTENT_OBFS_KEY);
        final String up = intent.getStringExtra(INTENT_UP_LIMIT);
        final String down = intent.getStringExtra(INTENT_DOWN_LIMIT);
        final int recvWinConn = intent.getIntExtra(INTENT_RECV_WIN_CONN, 262144);
        final int recvWin = intent.getIntExtra(INTENT_RECV_WIN, 4194304);
        final int coreCount = intent.getIntExtra(INTENT_CORE_COUNT, 4);
        final String tunHost = intent.getStringExtra(INTENT_TUNNEL_HOST);
        final String tunUser = intent.getStringExtra(INTENT_TUNNEL_USER);

        // Notifications on Oreo and above need a channel
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            String NOTIFICATION_CHANNEL_ID = "net.typeblog.socks";
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_NONE);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager).createNotificationChannel(channel);
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Create the notification
        int NOTIFICATION_ID = 1;
        int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            intentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent;
        contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), intentFlags);
        startForeground(NOTIFICATION_ID, builder
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(String.format(getString(R.string.notify_msg), name))
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .build());

        // Create an fd.
        configure(name, route, perApp, appBypass, appList, ipv6);

        if (DEBUG)
            Log.d(TAG, "fd: " + mInterface.getFd());

        if (mInterface != null) {
            // FIX: Jalankan start() di thread terpisah agar Main Thread (UI) tidak Freeze/ANR
            new Thread(() -> {
                start(mInterface.getFd(), server, port, username, passwd, dns, dnsPort, ipv6, udpgw,
                        obfs, up, down, recvWinConn, recvWin, coreCount, tunHost, tunUser);
            }).start();
        }

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stopMe();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopMe();
    }

    private void stopMe() {
        stopForeground(true);

        // FIX: Hancurkan proses secara paksa (Mencegah Zombie proses yang makan baterai)
        for (Process p : mNativeDaemons) {
            if (p != null) p.destroy();
        }
        mNativeDaemons.clear();

        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");

        // Fallback untuk berjaga-jaga
        Utility.exec("pkill -9 -f libuz.so");
        Utility.exec("pkill -9 -f libload.so");
        Utility.exec("pkill -9 -f libpdnsd.so");
        Utility.exec("pkill -9 -f libtun2socks.so");

        try {
            if (mInterface != null) {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        stopSelf();
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6) {
        Builder b = new Builder();
        b.setMtu(1500)
                .setSession(name)
                .addAddress("26.26.26.1", 24)
                .addDnsServer("8.8.8.8");

        if (ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);

        b.addDnsServer("8.8.8.8");
        b.addRoute("8.8.8.8", 32);

        if (!perApp) {
            try {
                b.addDisallowedApplication("net.typeblog.socks");
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            if (bypass) {
                try {
                    b.addDisallowedApplication("net.typeblog.socks");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                for (String p : apps) {
                    if (TextUtils.isEmpty(p)) continue;
                    try {
                        b.addDisallowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                for (String p : apps) {
                    if (TextUtils.isEmpty(p) || p.trim().equals("net.typeblog.socks")) continue;
                    try {
                        b.addAllowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        mInterface = b.establish();
    }

    private void start(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw,
                       String obfs, String up, String down, int recvWinConn, int recvWin, int coreCount,
                       String tunHost, String tunUser) {
        // Start DNS daemon first
        Utility.makePdnsdConf(this, dns, dnsPort);

        // FIX: Simpan track dari native process (libpdnsd)
        Process pdnsd = Utility.startDaemon(String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf",
                getApplicationInfo().nativeLibraryDir, getFilesDir()));
        if (pdnsd != null) mNativeDaemons.add(pdnsd);

        // Start libuz.so instances
        StringBuilder tunnels = new StringBuilder();
        String serverPorts = "6000-7750,7751-9500,9501-11225,11251-13000,13001-14750,14751-16500,16501-18250,18251-19999";
        for (int i = 0; i < coreCount; i++) {
            int listenPort = 1080 + i;
            String jsonConfig = String.format(Locale.US,
                    "{\"server\":\"%s:%s\",\"obfs\":\"%s\",\"auth\":\"%s\",\"socks5\":{\"listen\":\"127.0.0.1:%d\"},\"insecure\":true",
                    tunHost, serverPorts, obfs, tunUser, listenPort);

            if (!"0".equals(up)) {
                jsonConfig += String.format(Locale.US, ",\"up\":\"%s\"", up);
            }
            if (!"0".equals(down)) {
                jsonConfig += String.format(Locale.US, ",\"down\":\"%s\"", down);
            }

            jsonConfig += String.format(Locale.US, ",\"recvwindowconn\":%d,\"recvwindow\":%d}",
                    recvWinConn, recvWin);

            String[] uzCmd = {
                    getApplicationInfo().nativeLibraryDir + "/libuz.so",
                    "-s", obfs,
                    "--config", jsonConfig
            };

            // FIX: Simpan track dari proses libuz (Hindari raw thread)
            Process pUz = Utility.startDaemon(uzCmd);
            if (pUz != null) mNativeDaemons.add(pUz);
            
            tunnels.append("127.0.0.1:").append(listenPort).append(" ");
        }

        // Start libload.so
        int loadPort = 7777;
        String[] tunnelList = tunnels.toString().trim().split(" ");
        String[] loadCmd = new String[6 + tunnelList.length];
        loadCmd[0] = getApplicationInfo().nativeLibraryDir + "/libload.so";
        loadCmd[1] = "-lhost";
        loadCmd[2] = "127.0.0.1";
        loadCmd[3] = "-lport";
        loadCmd[4] = String.valueOf(loadPort);
        loadCmd[5] = "-tunnel";
        java.lang.System.arraycopy(tunnelList, 0, loadCmd, 6, tunnelList.length);

        // FIX: Simpan track libload.so
        Process pLoad = Utility.startDaemon(loadCmd);
        if (pLoad != null) mNativeDaemons.add(pLoad);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}

        String command = String.format(Locale.US,
                "%s/libtun2socks.so --netif-ipaddr 26.26.26.2"
                        + " --netif-netmask 255.255.255.0"
                        + " --socks-server-addr 127.0.0.1:%d"
                        + " --tunfd %d"
                        + " --tunmtu 1500"
                        + " --loglevel 3"
                        + " --pid %s/tun2socks.pid"
                        + " --sock %s/sock_path"
                , getApplicationInfo().nativeLibraryDir, loadPort, fd, getFilesDir(), getApplicationInfo().dataDir);

        if (ipv6) {
            command += " --netif-ip6addr fdfe:dcba:9876::2";
        }

        command += " --dnsgw 26.26.26.1:8091";

        if (udpgw != null) {
            command += " --udpgw-remote-server-addr " + udpgw;
        }

        if (DEBUG) {
            Log.d(TAG, command);
        }

        // FIX: Hapus Utility.exec yang blocking dan gantikan tracking libtun2socks
        Process pTun = Utility.startDaemon(command);
        if (pTun != null) {
            mNativeDaemons.add(pTun);
        } else {
            stopMe();
            return;
        }

        // Try to send the Fd through socket.
        int i = 0;
        while (i < 5) {
            if (System.sendfd(fd, getApplicationInfo().dataDir + "/sock_path") != -1) {
                mRunning = true;
                return;
            }

            i++;

            try {
                Thread.sleep(1000L * i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        stopMe();
    }
}
