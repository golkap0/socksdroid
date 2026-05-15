package net.typeblog.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.net.VpnService;
import android.os.Build;
import android.os.FileObserver;
import android.os.PowerManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private volatile boolean mRunning = false;
    private volatile boolean mStarting = false;
    private final IBinder mBinder = new VpnBinder();
    private SocksForwarder mForwarder;
    private ExecutorService mExecutor;
    private PowerManager.WakeLock mWakeLock;
    private final List<Future<?>> mFutures = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SocksDroid::VpnLock");
        mWakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (DEBUG) {
            Log.d(TAG, "starting");
        }

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
        final int dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 9953);
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

        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire(10 * 60 * 1000L); // 10 minutes
        }

        // Create an fd.
        configure(name, route, perApp, appBypass, appList, ipv6, TextUtils.isEmpty(dns) ? "9.9.9.9" : dns);

        if (mInterface != null) {
            if (DEBUG)
                Log.d(TAG, "fd: " + mInterface.getFd());

            mStarting = true;
            final int fd = mInterface.getFd();

            if (mExecutor == null || mExecutor.isShutdown()) {
                mExecutor = Executors.newFixedThreadPool(coreCount + 5);
            }

            mFutures.add(mExecutor.submit(() -> {
                try {
                    start(fd, server, port, username, passwd, TextUtils.isEmpty(dns) ? "9.9.9.9" : dns, dnsPort, ipv6, udpgw,
                            obfs, up, down, recvWinConn, recvWin, coreCount, tunHost, tunUser);
                } finally {
                    mStarting = false;
                }
            }));
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
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        stopForeground(true);

        if (mForwarder != null) {
            mForwarder.stopForwarder();
            mForwarder = null;
        }

        synchronized (mFutures) {
            for (Future<?> future : mFutures) {
                future.cancel(true);
            }
            mFutures.clear();
        }

        if (mExecutor != null) {
            mExecutor.shutdownNow();
            try {
                mExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            mExecutor = null;
        }

        // SIGTERM first
        Utility.exec("pkill -15 -f libuz.so");
        Utility.exec("pkill -15 -f libload.so");
        Utility.exec("pkill -15 -f libpdnsd.so");
        Utility.exec("pkill -15 -f libtun2socks.so");

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        // SIGKILL after grace period
        Utility.exec("pkill -9 -f libuz.so");
        Utility.exec("pkill -9 -f libload.so");
        Utility.exec("pkill -9 -f libpdnsd.so");
        Utility.exec("pkill -9 -f libtun2socks.so");

        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");

        if (mInterface != null) {
            try {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mInterface = null;
        }

        mRunning = false;
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
                e.printStackTrace();
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
                    e.printStackTrace();
                }

                for (String p : apps) {
                    if (TextUtils.isEmpty(p))
                        continue;

                    try {
                        b.addDisallowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
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

            // These are long-running processes, use timeout 0
            mFutures.add(mExecutor.submit(() -> Utility.exec(uzCmd, 0)));
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

        // Also long-running
        mFutures.add(mExecutor.submit(() -> Utility.exec(loadCmd, 0)));

        // Poll for SOCKS port readiness instead of fixed sleep
        for (int i = 0; i < 10; i++) {
            boolean allReady = true;
            for (int j = 0; j < workerCoreCount; j++) {
                try (Socket s = new Socket("127.0.0.1", 1080 + j)) {
                    // connected
                } catch (IOException e) {
                    allReady = false;
                    break;
                }
            }
            if (allReady) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

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

        final String sockPath = getApplicationInfo().dataDir + "/sock_path";
        final CountDownLatch latch = new CountDownLatch(1);
        FileObserver observer = new FileObserver(getApplicationInfo().dataDir, FileObserver.CREATE) {
            @Override
            public void onEvent(int event, String path) {
                if ("sock_path".equals(path)) {
                    latch.countDown();
                }
            }
        };
        observer.startWatching();

        // libtun2socks might also be long-running, but often it backgrounds itself.
        // To be safe, use 0 and rely on stopMe() pkill.
        mFutures.add(mExecutor.submit(() -> {
            if (Utility.exec(command, 0) != 0) {
                stopMe();
            }
        }));

        try {
            if (new File(sockPath).exists() || latch.await(10, TimeUnit.SECONDS)) {
                // Try several times as the socket might not be ready immediately after file creation
                for (int i = 0; i < 5; i++) {
                    if (System.sendfd(fd, sockPath) != -1) {
                        mRunning = true;
                        observer.stopWatching();
                        return;
                    }
                    Thread.sleep(500);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            observer.stopWatching();
        }

        // Should not get here. Must be a failure.
        stopMe();
    }

    private static class SocksForwarder extends Thread {
        private final int listenPort;
        private final String targetHost;
        private final int targetPort;
        private final int proxyPort;
        private ServerSocket serverSocket;
        private static final int SOCKET_TIMEOUT_MS = 30_000;

        private final ExecutorService executor = new ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<Runnable>(), new ThreadPoolExecutor.CallerRunsPolicy()
        );

        private Socket persistentProxy;
        private InputStream pIn;
        private OutputStream pOut;

        public SocksForwarder(int listenPort, String targetHost, int targetPort, int proxyPort) {
            this.listenPort = listenPort;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.proxyPort = proxyPort;
        }

        private synchronized Socket getPersistentProxy() throws IOException {
            if (persistentProxy == null || persistentProxy.isClosed() || !persistentProxy.isConnected()) {
                persistentProxy = new Socket();
                persistentProxy.setTcpNoDelay(true);
                persistentProxy.setKeepAlive(true);
                persistentProxy.setSoTimeout(SOCKET_TIMEOUT_MS);
                persistentProxy.connect(new java.net.InetSocketAddress("127.0.0.1", proxyPort), SOCKET_TIMEOUT_MS);

                InputStream in = persistentProxy.getInputStream();
                OutputStream out = persistentProxy.getOutputStream();

                // SOCKS5 Greeting
                out.write(new byte[]{0x05, 0x01, 0x00});
                byte[] greetingReply = new byte[2];
                if (!readFully(in, greetingReply) || greetingReply[1] != 0x00) {
                    persistentProxy.close();
                    throw new IOException("SOCKS5 handshake failed");
                }

                // SOCKS5 CONNECT to DNS server
                byte[] ip = InetAddress.getByName(targetHost).getAddress();
                byte[] request = new byte[6 + ip.length];
                request[0] = 0x05;
                request[1] = 0x01; // CONNECT
                request[2] = 0x00;
                request[3] = 0x01; // IPv4
                java.lang.System.arraycopy(ip, 0, request, 4, ip.length);
                request[4 + ip.length] = (byte) (targetPort >> 8);
                request[5 + ip.length] = (byte) (targetPort & 0xFF);
                out.write(request);

                byte[] replyHeader = new byte[4];
                if (!readFully(in, replyHeader) || replyHeader[1] != 0x00) {
                    persistentProxy.close();
                    throw new IOException("SOCKS5 CONNECT failed");
                }
                int atyp = replyHeader[3] & 0xFF;
                if (atyp == 0x01) readFully(in, new byte[6]);
                else if (atyp == 0x04) readFully(in, new byte[18]);
                else if (atyp == 0x03) {
                    int dlen = in.read();
                    if (dlen == -1) {
                        persistentProxy.close();
                        throw new IOException("Unexpected EOF");
                    }
                    readFully(in, new byte[dlen + 2]);
                }

                pIn = in;
                pOut = out;
            }
            return persistentProxy;
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(listenPort, 50, InetAddress.getByName("127.0.0.1"));
                while (!isInterrupted()) {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
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

            synchronized (this) {
                if (persistentProxy != null) {
                    try { persistentProxy.close(); } catch (IOException ignored) {}
                }
            }

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
            try {
                InputStream inClient = client.getInputStream();
                OutputStream outClient = client.getOutputStream();

                byte[] lenBuf = new byte[2];
                while (readFully(inClient, lenBuf)) {
                    int len = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
                    byte[] query = new byte[len];
                    if (!readFully(inClient, query)) break;

                    Socket proxy = getPersistentProxy();
                    synchronized (proxy) {
                        try {
                            pOut.write(lenBuf);
                            pOut.write(query);
                            pOut.flush();

                            if (!readFully(pIn, lenBuf)) {
                                proxy.close();
                                break;
                            }
                            int rlen = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
                            byte[] reply = new byte[rlen];
                            if (!readFully(pIn, reply)) {
                                proxy.close();
                                break;
                            }

                            outClient.write(lenBuf);
                            outClient.write(reply);
                            outClient.flush();
                        } catch (IOException e) {
                            proxy.close();
                            throw e;
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }

        private boolean readFully(InputStream in, byte[] buffer) throws IOException {
            int total = 0;
            while (total < buffer.length) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read == -1) return false;
                total += read;
            }
            return true;
        }
    }
}
