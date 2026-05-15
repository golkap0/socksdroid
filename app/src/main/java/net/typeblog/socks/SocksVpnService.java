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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
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
    private ScheduledExecutorService mRetryExecutor;
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

        if (mRetryExecutor != null) {
            mRetryExecutor.shutdownNow();
            mRetryExecutor = null;
        }

        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");

        // Kill native processes
        Utility.exec("pkill -9 -f libuz.so");
        Utility.exec("pkill -9 -f libload.so");
        Utility.exec("pkill -9 -f libpdnsd.so");
        Utility.exec("pkill -9 -f libtun2socks.so");

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
        // Start DNS forwarder to bypass port 53 blocking
        int forwarderPort = 8092;
        int loadBalancerPort = 7777;
        mForwarder = new SocksForwarder(forwarderPort, dns, dnsPort, loadBalancerPort);
        mForwarder.start();

        // Start DNS daemon first
        Utility.makePdnsdConf(this, "127.0.0.1", forwarderPort);

        Utility.exec(String.format(Locale.US, "sh -c '%s/libpdnsd.so -c %s/pdnsd.conf > /dev/null 2>&1'",
                getApplicationInfo().nativeLibraryDir, getFilesDir()));

        // Start libuz.so instances
        StringBuilder tunnels = new StringBuilder();
        String serverPorts = "6000-7750,7751-9500,9501-11225,11251-13000,13001-14750,14751-16500,16501-18250,18251-19999";
        int listenPort = 1080;
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

        String uzCmd = String.format(Locale.US, "sh -c '%s/libuz.so -s %s --config %s > /dev/null 2>&1'",
                getApplicationInfo().nativeLibraryDir + "/libuz.so", obfs, jsonConfig.replace("\"", "\\\""));

        new Thread(() -> Utility.exec(uzCmd)).start();
        tunnels.append("127.0.0.1:").append(listenPort).append(" ");

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

        String loadCmdStr = getApplicationInfo().nativeLibraryDir + "/libload.so -lhost 127.0.0.1 -lport " + loadPort + " -tunnel " + tunnels.toString().trim();
        CountDownLatch loadLatch = new CountDownLatch(1);
        new Thread(() -> {
            Utility.exec("sh -c '" + loadCmdStr + " > /dev/null 2>&1'");
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

        if (Utility.exec("sh -c '" + command + " > /dev/null 2>&1'") != 0) {
            stopMe();
            return;
        }

        // Try to send the Fd through socket with non-blocking retries.
        mRetryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        final int[] retryCount = {0};
        final String sockPath = getApplicationInfo().dataDir + "/sock_path";

        mRetryExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (System.sendfd(fd, sockPath) != -1) {
                    updateState(true);
                    if (mRetryExecutor != null) mRetryExecutor.shutdown();
                } else if (retryCount[0] < 5) {
                    retryCount[0]++;
                    if (mRetryExecutor != null) mRetryExecutor.schedule(this, retryCount[0], TimeUnit.SECONDS);
                } else {
                    stopMe();
                    if (mRetryExecutor != null) mRetryExecutor.shutdown();
                }
            }
        }, 0, TimeUnit.SECONDS);
    }

    private class SocksForwarder extends Thread {
        private final int listenPort;
        private final String targetHost;
        private final int targetPort;
        private final int proxyPort;
        private Selector selector;
        private ServerSocketChannel serverChannel;
        private volatile boolean running = true;

        private class Session {
            SocketChannel client;
            SocketChannel proxy;
            ByteBuffer clientBuffer = ByteBuffer.allocateDirect(16384);
            ByteBuffer proxyBuffer = ByteBuffer.allocateDirect(16384);
            int state = 0; // 0: Handshake, 1: Connect, 2: Reply, 3: Forwarding
        }

        public SocksForwarder(int listenPort, String targetHost, int targetPort, int proxyPort) {
            this.listenPort = listenPort;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.proxyPort = proxyPort;
        }

        @Override
        public void run() {
            try {
                selector = Selector.open();
                serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress("127.0.0.1", listenPort));
                serverChannel.configureBlocking(false);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);

                while (running) {
                    if (selector.select(1000) == 0) continue;
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            accept();
                        } else if (key.isConnectable()) {
                            finishConnect(key);
                        } else if (key.isReadable()) {
                            read(key);
                        } else if (key.isWritable()) {
                            write(key);
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                stopForwarder();
            }
        }

        private void accept() throws IOException {
            SocketChannel client = serverChannel.accept();
            client.configureBlocking(false);
            Session session = new Session();
            session.client = client;
            client.register(selector, SelectionKey.OP_READ, session);

            SocketChannel proxy = SocketChannel.open();
            proxy.configureBlocking(false);
            session.proxy = proxy;
            if (proxy.connect(new InetSocketAddress("127.0.0.1", proxyPort))) {
                onProxyConnected(session);
            } else {
                proxy.register(selector, SelectionKey.OP_CONNECT, session);
            }
        }

        private void finishConnect(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Session session = (Session) key.attachment();
            if (channel.finishConnect()) {
                onProxyConnected(session);
            }
        }

        private void onProxyConnected(Session session) throws IOException {
            session.proxy.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, session);
            session.client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, session);
            // Start SOCKS5 handshake - write to proxy, so put in clientBuffer
            session.clientBuffer.put(new byte[]{0x05, 0x01, 0x00});
            session.state = 0;
            updateInterests(session);
        }

        private void read(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Session session = (Session) key.attachment();
            ByteBuffer buffer = (channel == session.client) ? session.clientBuffer : session.proxyBuffer;

            int n = channel.read(buffer);
            if (n == -1) {
                closeSession(session);
                return;
            }

            if (session.state < 3) {
                handleControl(session);
            }
            updateInterests(session);
        }

        private void write(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Session session = (Session) key.attachment();
            ByteBuffer buffer = (channel == session.client) ? session.proxyBuffer : session.clientBuffer;

            buffer.flip();
            channel.write(buffer);
            buffer.compact();

            if (session.state < 3) {
                handleControl(session);
            }
            updateInterests(session);
        }

        private void handleControl(Session session) throws IOException {
            boolean changed = true;
            while (changed && session.state < 3) {
                changed = false;
                if (session.state == 0) { // Handshake response
                    session.proxyBuffer.flip();
                    if (session.proxyBuffer.remaining() >= 2) {
                        byte ver = session.proxyBuffer.get();
                        byte method = session.proxyBuffer.get();
                        if (ver == 0x05 && method == 0x00) {
                            session.proxyBuffer.compact();
                            // Send Connect request to proxy, so put in clientBuffer
                            InetAddress addr = InetAddress.getByName(targetHost);
                            byte[] ip = addr.getAddress();
                            session.clientBuffer.put(new byte[]{0x05, 0x01, 0x00, (byte) (ip.length == 4 ? 0x01 : 0x04)});
                            session.clientBuffer.put(ip);
                            session.clientBuffer.putShort((short) targetPort);
                            session.state = 1;
                            changed = true;
                        } else {
                            closeSession(session);
                            return;
                        }
                    } else {
                        session.proxyBuffer.compact();
                    }
                } else if (session.state == 1) { // Connect response header
                    session.proxyBuffer.flip();
                    if (session.proxyBuffer.remaining() >= 4) {
                        session.proxyBuffer.mark();
                        byte ver = session.proxyBuffer.get();
                        byte rep = session.proxyBuffer.get();
                        session.proxyBuffer.get(); // rsv
                        byte atyp = session.proxyBuffer.get();
                        if (ver == 0x05 && rep == 0x00) {
                            session.state = 2;
                            session.proxyBuffer.reset();
                            changed = true;
                        } else {
                            closeSession(session);
                            return;
                        }
                    } else {
                        session.proxyBuffer.compact();
                    }
                } else if (session.state == 2) { // Connect response body
                    // Already have 4 bytes at least from state 1 reset
                    if (session.proxyBuffer.remaining() >= 4) {
                        session.proxyBuffer.mark();
                        session.proxyBuffer.position(session.proxyBuffer.position() + 3);
                        byte atyp = session.proxyBuffer.get();
                        int addrLen = (atyp == 0x01) ? 4 : (atyp == 0x04) ? 16 : (atyp == 0x03) ? (session.proxyBuffer.get() & 0xFF) : -1;
                        if (addrLen != -1 && session.proxyBuffer.remaining() >= addrLen + 2) {
                            session.proxyBuffer.position(session.proxyBuffer.position() + addrLen + 2);
                            session.proxyBuffer.compact();
                            session.state = 3; // Forwarding
                            changed = true;
                        } else {
                            session.proxyBuffer.reset();
                            session.proxyBuffer.compact();
                        }
                    } else {
                        session.proxyBuffer.compact();
                    }
                }
            }
        }

        private void updateInterests(Session session) {
            if (!session.client.isOpen() || !session.proxy.isOpen()) return;

            int clientOps = 0;
            if (session.clientBuffer.hasRemaining()) clientOps |= SelectionKey.OP_READ;
            if (session.proxyBuffer.position() > 0) clientOps |= SelectionKey.OP_WRITE;

            int proxyOps = 0;
            if (session.proxyBuffer.hasRemaining()) proxyOps |= SelectionKey.OP_READ;
            if (session.clientBuffer.position() > 0) proxyOps |= SelectionKey.OP_WRITE;

            try {
                session.client.register(selector, clientOps, session);
                session.proxy.register(selector, proxyOps, session);
            } catch (Exception ignored) {}
        }

        private void closeSession(Session session) {
            try { session.client.close(); } catch (IOException ignored) {}
            try { session.proxy.close(); } catch (IOException ignored) {}
        }

        public void stopForwarder() {
            running = false;
            if (selector != null) selector.wakeup();
            try { if (serverChannel != null) serverChannel.close(); } catch (IOException ignored) {}
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
        }
    }
}
