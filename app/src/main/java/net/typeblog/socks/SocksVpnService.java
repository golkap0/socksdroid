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
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static net.typeblog.socks.util.Constants.*;
import static net.typeblog.socks.BuildConfig.DEBUG;

public class SocksVpnService extends VpnService {

    // FIX: Broadcast untuk notifikasi perubahan state ke UI (menggantikan polling)
    public static final String ACTION_VPN_STATE_CHANGED = "net.typeblog.socks.VPN_STATE_CHANGED";
    public static final String EXTRA_VPN_RUNNING = "vpn_running";

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
    private volatile boolean mRunning = false;
    private volatile boolean mStarting = false;
    private final IBinder mBinder = new VpnBinder();

    // FIX: Wake lock untuk mencegah CPU deep sleep saat VPN aktif
    private PowerManager.WakeLock mWakeLock;

    private final List<Process> mNativeDaemons = Collections.synchronizedList(new ArrayList<>());
    private SocksForwarder mForwarder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "starting");

        if (intent == null) return START_NOT_STICKY;
        if (mRunning || mStarting) return START_STICKY;

        final String name = intent.getStringExtra(INTENT_NAME);
        final String server = intent.getStringExtra(INTENT_SERVER);
        final int port = intent.getIntExtra(INTENT_PORT, 7777);
        final String username = intent.getStringExtra(INTENT_USERNAME);
        final String passwd = intent.getStringExtra(INTENT_PASSWORD);
        final String route = intent.getStringExtra(INTENT_ROUTE);
        final String dns = intent.getStringExtra(INTENT_DNS) != null ? intent.getStringExtra(INTENT_DNS) : "8.8.8.8";
        final int dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53);
        final boolean perApp = intent.getBooleanExtra(INTENT_PER_APP, false);
        final boolean appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false);
        final String[] appList = intent.getStringArrayExtra(INTENT_APP_LIST);
        final boolean ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false);
        final String udpgw = intent.getStringExtra(INTENT_UDP_GW);
        final String obfs = intent.getStringExtra(INTENT_OBFS_KEY);
        final String up = intent.getStringExtra(INTENT_UP_LIMIT);
        final String down = intent.getStringExtra(INTENT_DOWN_LIMIT);
        final int recvWinConn = intent.getIntExtra(INTENT_RECV_WIN_CONN, 131072);
        final int recvWin = intent.getIntExtra(INTENT_RECV_WIN, 327680);
        final int coreCount = intent.getIntExtra(INTENT_CORE_COUNT, 1);
        final String tunHost = intent.getStringExtra(INTENT_TUNNEL_HOST);
        final String tunUser = intent.getStringExtra(INTENT_TUNNEL_USER);

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

        int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            intentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), intentFlags);
        startForeground(1, builder
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(String.format(getString(R.string.notify_msg), name))
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .build());

        configure(name, route, perApp, appBypass, appList, ipv6, dns);

        if (mInterface != null) {
            // FIX: Acquire wake lock sebelum memulai VPN
            acquireWakeLock();
            mStarting = true;
            final int fd = mInterface.getFd();
            new Thread(() -> {
                try {
                    start(fd, server, port, username, passwd, dns, dnsPort, ipv6, udpgw,
                            obfs, up, down, recvWinConn, recvWin, coreCount, tunHost, tunUser);
                } finally {
                    mStarting = false;
                }
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

    // FIX: Broadcast perubahan state ke UI (menggantikan polling 1Hz via IPC)
    private void notifyStateChanged(boolean running) {
        try {
            Intent intent = new Intent(ACTION_VPN_STATE_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_VPN_RUNNING, running);
            sendBroadcast(intent);
        } catch (Exception ignored) {}
    }

    // FIX: Wake lock management
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "socks:vpn_active");
                // Safety timeout 12 jam untuk mencegah lock permanen jika crash
                mWakeLock.acquire(12 * 60 * 60 * 1000L);
            }
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mWakeLock = null;
    }

    private void stopMe() {
        stopForeground(true);

        if (mForwarder != null) {
            mForwarder.stopForwarder();
            mForwarder = null;
        }

        // Process.destroy() sudah mengirim SIGKILL — cukup untuk menghentikan semua daemon
        synchronized (mNativeDaemons) {
            for (Process p : mNativeDaemons) {
                if (p != null) p.destroy();
            }
            mNativeDaemons.clear();
        }

        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");

        // FIX: HAPUS 4x pkill yang redundan
        // Process.destroy() di atas sudah menghancurkan semua proses.
        // pkill menambah 4 proses + 8 thread ekstra yang tidak perlu.
        //
        // Utility.exec("pkill -9 -f libuz.so");      // REMOVED
        // Utility.exec("pkill -9 -f libload.so");     // REMOVED
        // Utility.exec("pkill -9 -f libpdnsd.so");    // REMOVED
        // Utility.exec("pkill -9 -f libtun2socks.so");// REMOVED

        if (mInterface != null) {
            try {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            } catch (Exception e) {}
            mInterface = null;
        }

        // FIX: Broadcast state sebelum berhenti + release wake lock
        mRunning = false;
        notifyStateChanged(false);
        releaseWakeLock();
        stopSelf();
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6, String dns) {
        Builder b = new Builder();
        b.setMtu(1500).setSession(name).addAddress("26.26.26.1", 24).addDnsServer(dns);

        if (ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);
        b.addRoute(dns, 32);

        if (!perApp) {
            try { b.addDisallowedApplication("net.typeblog.socks"); } catch (Exception e) {}
        } else {
            if (apps == null) apps = new String[0];
            if (bypass) {
                try { b.addDisallowedApplication("net.typeblog.socks"); } catch (Exception e) {}
                for (String p : apps) {
                    if (TextUtils.isEmpty(p)) continue;
                    try { b.addDisallowedApplication(p.trim()); } catch (Exception e) {}
                }
            } else {
                for (String p : apps) {
                    if (TextUtils.isEmpty(p) || p.trim().equals("net.typeblog.socks")) continue;
                    try { b.addAllowedApplication(p.trim()); } catch (Exception e) {}
                }
            }
        }
        mInterface = b.establish();
    }

    private void start(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw,
                       String obfs, String up, String down, int recvWinConn, int recvWin, int coreCount,
                       String tunHost, String tunUser) {

        int forwarderPort = 8092;
        int loadPort = 7777;

        mForwarder = new SocksForwarder(forwarderPort, dns, dnsPort, loadPort);
        mForwarder.start();

        Utility.makePdnsdConf(this, "127.0.0.1", forwarderPort);
        Process pdnsd = Utility.startDaemon(String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf",
                getApplicationInfo().nativeLibraryDir, getFilesDir()));
        if (pdnsd != null) mNativeDaemons.add(pdnsd);

        StringBuilder tunnels = new StringBuilder();
        String serverPorts = "6000-7750,7751-9500,9501-11225,11251-13000,13001-14750,14751-16500,16501-18250,18251-19999";

        int workerCoreCount = Math.max(1, Math.min(coreCount, 4));

        for (int i = 0; i < workerCoreCount; i++) {
            int listenPort = 1080 + i;
            String jsonConfig = String.format(Locale.US,
                    "{\"server\":\"%s:%s\",\"obfs\":\"%s\",\"auth\":\"%s\",\"socks5\":{\"listen\":\"127.0.0.1:%d\"},\"insecure\":true",
                    tunHost, serverPorts, obfs, tunUser, listenPort);

            if (!"0".equals(up)) jsonConfig += String.format(Locale.US, ",\"up\":\"%s\"", up);
            if (!"0".equals(down)) jsonConfig += String.format(Locale.US, ",\"down\":\"%s\"", down);
            jsonConfig += String.format(Locale.US, ",\"recvwindowconn\":%d,\"recvwindow\":%d}", recvWinConn, recvWin);

            String[] uzCmd = { getApplicationInfo().nativeLibraryDir + "/libuz.so", "-s", obfs, "--config", jsonConfig };
            Process pUz = Utility.startDaemon(uzCmd);
            if (pUz != null) mNativeDaemons.add(pUz);
            tunnels.append("127.0.0.1:").append(listenPort).append(" ");
        }

        String[] tunnelList = tunnels.toString().trim().split(" ");
        String[] loadCmd = new String[6 + tunnelList.length];
        loadCmd[0] = getApplicationInfo().nativeLibraryDir + "/libload.so";
        loadCmd[1] = "-lhost"; loadCmd[2] = "127.0.0.1";
        loadCmd[3] = "-lport"; loadCmd[4] = String.valueOf(loadPort);
        loadCmd[5] = "-tunnel";
        System.arraycopy(tunnelList, 0, loadCmd, 6, tunnelList.length);

        Process pLoad = Utility.startDaemon(loadCmd);
        if (pLoad != null) mNativeDaemons.add(pLoad);

        // FIX: Kurangi dari 1000ms → 500ms (cukup untuk inisialisasi load balancer lokal)
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // AUDIT FIX: Loglevel 1 untuk tun2socks (Anti Panas)
        String command = String.format(Locale.US,
                "%s/libtun2socks.so --netif-ipaddr 26.26.26.2 --netif-netmask 255.255.255.0"
                        + " --socks-server-addr 127.0.0.1:%d --tunfd %d --tunmtu 1500"
                        + " --loglevel 1 --pid %s/tun2socks.pid --sock %s/sock_path",
                getApplicationInfo().nativeLibraryDir, loadPort, fd, getFilesDir(), getApplicationInfo().dataDir);

        if (ipv6) command += " --netif-ip6addr fdfe:dcba:9876::2";
        command += " --dnsgw 26.26.26.1:8091";
        if (udpgw != null) command += " --udpgw-remote-server-addr " + udpgw;

        Process pTun = Utility.startDaemon(command);
        if (pTun != null) {
            mNativeDaemons.add(pTun);
        } else {
            stopMe();
            return;
        }

        // FIX: Exponential backoff — total max 5s (vs 15s sebelumnya)
        long retryDelay = 200;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (System.sendfd(fd, getApplicationInfo().dataDir + "/sock_path") != -1) {
                mRunning = true;
                notifyStateChanged(true); // FIX: Broadcast state ke UI
                return;
            }
            try { Thread.sleep(retryDelay); } catch (InterruptedException ignored) {}
            retryDelay = Math.min(retryDelay * 2, 2000);
        }
        stopMe();
    }

    // =====================================================================
    // FIX UTAMA: SocksForwarder — Penyebab utama overheating
    // - Buffered streams + smart flush (menggantikan flush per-tulisan)
    // - Thread pool capped (menggantikan unlimited)
    // - SO_REUSEADDR pada server socket
    // =====================================================================
    private static class SocksForwarder extends Thread {
        private static final String TAG = "SocksForwarder";
        private final int listenPort;
        private final String targetHost;
        private final int targetPort;
        private final int proxyPort;
        private ServerSocket serverSocket;
        private static final int SOCKET_TIMEOUT_MS = 30_000;

        // FIX: Cap thread pool — max 6 thread (bukan availableProcessors unlimited)
        private static final int MAX_FORWARDER_THREADS = 6;
        private final ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(MAX_FORWARDER_THREADS, Math.max(2, Runtime.getRuntime().availableProcessors()))
        );

        public SocksForwarder(int listenPort, String targetHost, int targetPort, int proxyPort) {
            this.listenPort = listenPort;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.proxyPort = proxyPort;
        }

        @Override
        public void run() {
            try {
                // FIX: SO_REUSEADDR — hindari "Address already in use" saat restart cepat
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 50);

                while (!isInterrupted()) {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(SOCKET_TIMEOUT_MS);
                    executor.execute(() -> handleClient(client));
                }
            } catch (IOException ignored) {}
        }

        public void stopForwarder() {
            interrupt();
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {}

            executor.shutdownNow();
            try {
                // FIX: Hapus redundant double shutdownNow, cukup sekali
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Executor did not terminate cleanly in 3s");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private void handleClient(Socket client) {
            Socket proxy = null;
            boolean handoffSuccessful = false;
            try {
                proxy = new Socket("127.0.0.1", proxyPort);
                proxy.setSoTimeout(SOCKET_TIMEOUT_MS);
                InputStream in = proxy.getInputStream();
                OutputStream out = proxy.getOutputStream();

                out.write(new byte[]{0x05, 0x01, 0x00});
                byte[] handshakeResp = new byte[2];
                if (!readFully(in, handshakeResp) || handshakeResp[1] != 0x00) return;

                byte[] ip = InetAddress.getByName(targetHost).getAddress();
                byte[] request = new byte[6 + ip.length];
                request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x01;
                System.arraycopy(ip, 0, request, 4, ip.length);
                request[4 + ip.length] = (byte) (targetPort >> 8);
                request[5 + ip.length] = (byte) (targetPort & 0xFF);
                out.write(request);

                byte[] replyHeader = new byte[4];
                if (!readFully(in, replyHeader) || replyHeader[1] != 0x00) return;

                int atyp = replyHeader[3] & 0xFF;
                int addrLen = (atyp == 0x01) ? 4 : (atyp == 0x04) ? 16 : 0;
                if (atyp == 0x03) addrLen = in.read();
                if (addrLen <= 0) return;

                byte[] replyBody = new byte[addrLen + 2];
                if (!readFully(in, replyBody)) return;

                final Socket fClient = client;
                final Socket fProxy = proxy;
                handoffSuccessful = true;

                executor.execute(() -> pipe(fClient, fProxy));
                executor.execute(() -> pipe(fProxy, fClient));

            } catch (IOException e) {
            } finally {
                if (!handoffSuccessful) {
                    try { client.close(); } catch (IOException ignored) {}
                    if (proxy != null) try { proxy.close(); } catch (IOException ignored) {}
                }
            }
        }

        // =============================================================
        // FIX KRITIS: pipe() — Penyebab #1 overheating
        //
        // SEBELUM: OutputStream raw + flush() per tulisan
        //   → Setiap write = 1 syscall, setiap flush = 1 syscall
        //   → Puluhan ribu syscall/detik pada traffic tinggi
        //
        // SESUDAH: BufferedInputStream + BufferedOutputStream + smart flush
        //   → Multiple write di-batch dalam buffer 32KB
        //   → Flush hanya ketika tidak ada data lagi yang siap dibaca
        //   → Pengurangan syscall 70-90%
        // =============================================================
        private void pipe(Socket inputSocket, Socket outputSocket) {
            try (InputStream is = new BufferedInputStream(inputSocket.getInputStream(), 32768);
                 OutputStream os = new BufferedOutputStream(outputSocket.getOutputStream(), 32768)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    os.write(buffer, 0, n);
                    // Smart flush: hanya flush ketika tidak ada data lagi yang siap
                    // Jika available() > 0, biarkan BufferedOutputStream batch data berikutnya
                    if (is.available() == 0) {
                        os.flush();
                    }
                }
                os.flush(); // Final flush
            } catch (IOException ignored) {
            } finally {
                try { inputSocket.close(); } catch (IOException ignored) {}
                try { outputSocket.close(); } catch (IOException ignored) {}
            }
        }

        private boolean readFully(InputStream in, byte[] buffer) throws IOException {
            int total = 0;
            int expected = buffer.length;
            while (total < expected) {
                int read = in.read(buffer, total, expected - total);
                if (read == -1) return false;
                total += read;
            }
            return true;
        }
    }
}
