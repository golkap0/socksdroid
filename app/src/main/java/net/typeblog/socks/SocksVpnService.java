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
import android.os.RemoteCallbackList;
import android.text.TextUtils;

import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static net.typeblog.socks.util.Constants.*;

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

        @Override
        public String getLogs() {
            synchronized (mLogBuffer) {
                return mLogBuffer.toString();
            }
        }

        @Override
        public void clearLogs() {
            synchronized (mLogBuffer) {
                mLogBuffer.setLength(0);
            }
        }

        @Override
        public void setLoggingEnabled(boolean enabled) {
            mLoggingEnabled = enabled;
        }

        @Override
        public boolean isLoggingEnabled() {
            return mLoggingEnabled;
        }

        @Override
        public void registerCallback(IVpnServiceCallback cb) {
            mCallbacks.register(cb);
            try {
                cb.onStateChanged(mRunning);
            } catch (Exception e) {
                // Ignore
            }
        }

        @Override
        public void unregisterCallback(IVpnServiceCallback cb) {
            mCallbacks.unregister(cb);
        }
    }

    private ParcelFileDescriptor mInterface;
    private volatile boolean mRunning = false;
    private volatile boolean mStarting = false;
    private final IBinder mBinder = new VpnBinder();
    private SocksForwarder mForwarder;
    private final StringBuilder mLogBuffer = new StringBuilder();
    private volatile boolean mLoggingEnabled = true;
    private final RemoteCallbackList<IVpnServiceCallback> mCallbacks = new RemoteCallbackList<>();

    private void updateState(boolean running) {
        mRunning = running;
        int n = mCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onStateChanged(running);
            } catch (Exception e) {
                // Ignore
            }
        }
        mCallbacks.finishBroadcast();
    }

    private void log(String msg) {
        if (mLoggingEnabled) {
            synchronized (mLogBuffer) {
                if (mLogBuffer.length() > 50000) {
                    // Optimized pruning: find a newline near the 10k mark to preserve log integrity
                    int pruneIdx = mLogBuffer.indexOf("\n", 10000);
                    if (pruneIdx != -1) {
                        mLogBuffer.delete(0, pruneIdx + 1);
                    } else {
                        mLogBuffer.delete(0, 10000);
                    }
                }
                mLogBuffer.append(msg).append("\n");
            }

            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onLogAdded(msg);
                } catch (Exception e) {
                    // Ignore
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            return START_STICKY;
        }

        if (mRunning || mStarting) {
            return START_STICKY;
        }

        final String name = intent.getStringExtra(INTENT_NAME);
        final String server = intent.getStringExtra(INTENT_SERVER);
        final int port = intent.getIntExtra(INTENT_PORT, 1080);
        final String username = intent.getStringExtra(INTENT_USERNAME);
        final String passwd = intent.getStringExtra(INTENT_PASSWORD);
        final String route = intent.getStringExtra(INTENT_ROUTE);
        final String dns = intent.getStringExtra(INTENT_DNS) != null ? intent.getStringExtra(INTENT_DNS) : "9.9.9.9";
        final int dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53);
        final boolean perApp = intent.getBooleanExtra(INTENT_PER_APP, false);
        final boolean appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false);
        final String[] appList = intent.getStringArrayExtra(INTENT_APP_LIST);
        final boolean ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false);
        final String udpgw = intent.getStringExtra(INTENT_UDP_GW);
        final String obfs = intent.getStringExtra(INTENT_OBFS_KEY) != null ? intent.getStringExtra(INTENT_OBFS_KEY) : "hu``hqb`c";
        final String up = intent.getStringExtra(INTENT_UP_LIMIT) != null ? intent.getStringExtra(INTENT_UP_LIMIT) : "5 Mbps";
        final String down = intent.getStringExtra(INTENT_DOWN_LIMIT) != null ? intent.getStringExtra(INTENT_DOWN_LIMIT) : "2 Mbps";
        final int recvWinConn = intent.getIntExtra(INTENT_RECV_WIN_CONN, 1048576);
        final int recvWin = intent.getIntExtra(INTENT_RECV_WIN, 3145728);
        final int coreCount = intent.getIntExtra(INTENT_CORE_COUNT, 1);
        final String tunHost = intent.getStringExtra(INTENT_TUNNEL_HOST) != null ? intent.getStringExtra(INTENT_TUNNEL_HOST) : "ssh-2.chice.me";
        final String tunUser = intent.getStringExtra(INTENT_TUNNEL_USER) != null ? intent.getStringExtra(INTENT_TUNNEL_USER) : "vpnstunnel-bnml0";

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
        configure(name, route, perApp, appBypass, appList, ipv6, TextUtils.isEmpty(dns) ? "9.9.9.9" : dns);

        if (mInterface != null) {
            mStarting = true;
            final int fd = mInterface.getFd();
            new Thread(() -> {
                try {
                    start(fd, server, port, username, passwd, TextUtils.isEmpty(dns) ? "9.9.9.9" : dns, dnsPort, ipv6, udpgw,
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

    private void stopMe() {
        stopForeground(true);

        if (mForwarder != null) {
            mForwarder.stopForwarder();
            mForwarder = null;
        }

        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");

        // Kill all native processes in a single execution to reduce overhead
        Utility.exec("pkill -9 -f 'libuz.so|libload.so|libpdnsd.so|libtun2socks.so'");

        if (mInterface != null) {
            try {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            } catch (Exception e) {
                // Ignore
            }
            mInterface = null;
        }

        updateState(false);
        stopSelf();
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6, String dns) {
        Builder b = new Builder();
        b.setMtu(1500)
                .setSession(name)
                .addAddress("26.26.26.1", 24)
                .addDnsServer(dns);

        if (ipv6) {
            // Route all IPv6 traffic
            b.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);

        // Add the default DNS
        // Note that this DNS is just a stub.
        // Actual DNS requests will be redirected through pdnsd.
        b.addDnsServer(dns);
        b.addRoute(dns, 32);

        // Do app routing
        if (!perApp) {
            // Just bypass myself
            try {
                b.addDisallowedApplication("net.typeblog.socks");
            } catch (Exception e) {
                // Ignore
            }
        } else {
            if (apps == null) {
                apps = new String[0];
            }
            if (bypass) {
                // First, bypass myself
                try {
                    b.addDisallowedApplication("net.typeblog.socks");
                } catch (Exception e) {
                    // Ignore
                }

                for (String p : apps) {
                    if (TextUtils.isEmpty(p))
                        continue;

                    try {
                        b.addDisallowedApplication(p.trim());
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            } else {
                for (String p : apps) {
                    if (TextUtils.isEmpty(p) || p.trim().equals("net.typeblog.socks")) {
                        continue;
                    }

                    try {
                        b.addAllowedApplication(p.trim());
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }

        mInterface = b.establish();
    }

    private void start(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw,
                       String obfs, String up, String down, int recvWinConn, int recvWin, int coreCount,
                       String tunHost, String tunUser) {
        int maxRecommendedCores = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int workerCoreCount = Math.max(1, Math.min(coreCount, maxRecommendedCores));

        // Start DNS forwarder to bypass port 53 blocking
        int forwarderPort = 8092;
        int loadBalancerPort = 7777;
        mForwarder = new SocksForwarder(forwarderPort, dns, dnsPort, loadBalancerPort);
        mForwarder.start();

        // Start DNS daemon first
        Utility.makePdnsdConf(this, "127.0.0.1", forwarderPort);

        Utility.exec(String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf",
                getApplicationInfo().nativeLibraryDir, getFilesDir()));

        // Start libuz.so instances
        StringBuilder tunnels = new StringBuilder();
        String serverPorts = "6000-7750,7751-9500,9501-11225,11251-13000,13001-14750,14751-16500,16501-18250,18251-19999";
        for (int i = 0; i < workerCoreCount; i++) {
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

            new Thread(() -> Utility.exec(uzCmd, line -> log("libuz: " + line))).start();
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

        CountDownLatch loadLatch = new CountDownLatch(1);
        new Thread(() -> {
            Utility.exec(loadCmd, line -> {
                if (line.contains("Listening")) {
                    loadLatch.countDown();
                }
            });
            // Also countdown if the process exits unexpectedly
            loadLatch.countDown();
        }).start();

        try {
            // Wait for libload to start listening, but with a timeout
            loadLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        String command = String.format(Locale.US,
                "%s/libtun2socks.so --netif-ipaddr 26.26.26.2"
                        + " --netif-netmask 255.255.255.0"
                        + " --socks-server-addr 127.0.0.1:%d"
                        + " --tunfd %d"
                        + " --tunmtu 1500"
                        + " --loglevel 0"
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

        if (Utility.exec(command) != 0) {
            stopMe();
            return;
        }

        // Try to send the Fd through socket.
        int i = 0;
        while (i < 5) {
            if (System.sendfd(fd, getApplicationInfo().dataDir + "/sock_path") != -1) {
                updateState(true);
                return;
            }

            i++;

            try {
                Thread.sleep(1000L * i);
            } catch (Exception e) {
                // Ignore
            }
        }

        // Should not get here. Must be a failure.
        stopMe();
    }

    private class SocksForwarder extends Thread {
        private final int listenPort;
        private final String targetHost;
        private final int targetPort;
        private final int proxyPort;
        private ServerSocket serverSocket;
        private static final int SOCKET_TIMEOUT_MS = 30_000;
        private final ExecutorService executor = new ThreadPoolExecutor(4, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100));

        public SocksForwarder(int listenPort, String targetHost, int targetPort, int proxyPort) {
            this.listenPort = listenPort;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.proxyPort = proxyPort;
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(listenPort, 50, InetAddress.getByName("127.0.0.1"));
                while (!isInterrupted()) {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(SOCKET_TIMEOUT_MS);
                    executor.execute(() -> handleClient(client));
                }
            } catch (IOException e) {
                // Closed
            }
        }

        public void stopForwarder() {
            interrupt();
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {}

            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
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

                // Handshake
                out.write(new byte[]{0x05, 0x01, 0x00});
                byte[] handshakeResp = new byte[2];
                if (!readFully(in, handshakeResp) || handshakeResp[1] != 0x00) {
                    return;
                }

                // Connect
                InetAddress addr = InetAddress.getByName(targetHost);
                byte[] ip = addr.getAddress();
                byte[] request = new byte[6 + ip.length];
                request[0] = 0x05;
                request[1] = 0x01; // CONNECT
                request[2] = 0x00;
                request[3] = (byte) (ip.length == 4 ? 0x01 : 0x04);
                java.lang.System.arraycopy(ip, 0, request, 4, ip.length);
                request[4 + ip.length] = (byte) (targetPort >> 8);
                request[5 + ip.length] = (byte) (targetPort & 0xFF);
                out.write(request);

                byte[] replyHeader = new byte[4];
                if (!readFully(in, replyHeader) || replyHeader[1] != 0x00) {
                    return;
                }
                int atyp = replyHeader[3] & 0xFF;
                int addrLen;
                if (atyp == 0x01) { // IPv4
                    addrLen = 4;
                } else if (atyp == 0x04) { // IPv6
                    addrLen = 16;
                } else if (atyp == 0x03) { // DOMAIN
                    int domainLen = in.read();
                    if (domainLen == -1) {
                        return;
                    }
                    addrLen = domainLen;
                } else {
                    return;
                }
                byte[] replyBody = new byte[addrLen + 2];
                if (!readFully(in, replyBody)) {
                    return;
                }

                // Forwarding
                final Socket fClient = client;
                final Socket fProxy = proxy;
                handoffSuccessful = true;
                executor.execute(() -> pipe(fClient, fProxy));
                executor.execute(() -> pipe(fProxy, fClient));
            } catch (IOException e) {
                // Ignore
            } finally {
                if (!handoffSuccessful) {
                    try { client.close(); } catch (IOException ignored) {}
                    if (proxy != null) try { proxy.close(); } catch (IOException ignored) {}
                }
            }
        }

        private void pipe(Socket s1, Socket s2) {
            try {
                InputStream is = s1.getInputStream();
                OutputStream os = s2.getOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    os.write(buffer, 0, n);
                }
            } catch (IOException ignored) {}
            finally {
                try { s1.close(); } catch (IOException ignored) {}
                try { s2.close(); } catch (IOException ignored) {}
            }
        }

        private boolean readFully(InputStream in, byte[] buffer) throws IOException {
            return readFully(in, buffer, buffer.length);
        }

        private boolean readFully(InputStream in, byte[] buffer, int expectedLength) throws IOException {
            int total = 0;
            while (total < expectedLength) {
                int read = in.read(buffer, total, expectedLength - total);
                if (read == -1) {
                    return false;
                }
                total += read;
            }
            return true;
        }
    }
}
