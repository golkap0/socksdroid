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

        @Override
        public void registerCallback(IVpnServiceCallback cb) {
            mCallbacks.register(cb);
        }

        @Override
        public void unregisterCallback(IVpnServiceCallback cb) {
            mCallbacks.unregister(cb);
        }
    }

    private static final String TAG = SocksVpnService.class.getSimpleName();

    private ParcelFileDescriptor mInterface;
    private final android.os.RemoteCallbackList<IVpnServiceCallback> mCallbacks = new android.os.RemoteCallbackList<>();
    private volatile boolean mRunning = false;
    private volatile boolean mStarting = false;
    private final IBinder mBinder = new VpnBinder();
    private SocksForwarder mForwarder;

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

        // Kill libuz.so and libload.so
        Utility.exec("pkill -9 -f libuz.so");
        Utility.exec("pkill -9 -f libload.so");
        Utility.exec("pkill -9 -f libpdnsd.so");
        Utility.exec("pkill -9 -f libtun2socks.so");

        if (mInterface != null) {
            try {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mInterface = null;
        }

        boolean wasRunning = mRunning;
        mRunning = false;
        if (wasRunning) broadcastState(false);
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
                       String obfs, String up, String down, int recvWinConn, int recvWin, int instanceCount,
                       String tunHost, String tunUser) {
        int workerInstanceCount = Math.max(1, instanceCount);

        // Start DNS forwarder to bypass port 53 blocking
        int forwarderPort = 8092;
        int loadBalancerPort = 7777;
        mForwarder = new SocksForwarder(forwarderPort, dns, dnsPort, loadBalancerPort);
        mForwarder.start();

        // Start DNS daemon first
        Utility.makePdnsdConf(this, "127.0.0.1", forwarderPort);

        Utility.exec(new String[]{"sh", "-c", String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf > /dev/null 2>&1",
                getApplicationInfo().nativeLibraryDir, getFilesDir())});

        // Start libuz.so instances
        StringBuilder tunnels = new StringBuilder();
        String serverPorts = "6000-7750,7751-9500,9501-11225,11251-13000,13001-14750,14751-16500,16501-18250,18251-19999";
        for (int i = 0; i < workerInstanceCount; i++) {
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
                    "sh", "-c",
                    String.format(Locale.US, "%s/libuz.so -s %s --config '%s' > /dev/null 2>&1",
                            getApplicationInfo().nativeLibraryDir, obfs, jsonConfig)
            };

            new Thread(() -> Utility.exec(uzCmd)).start();
            tunnels.append("127.0.0.1:").append(listenPort).append(" ");
        }

        // Start libload.so
        int loadPort = 7777;
        String[] tunnelList = tunnels.toString().trim().split(" ");
        StringBuilder loadCmdStr = new StringBuilder();
        loadCmdStr.append(getApplicationInfo().nativeLibraryDir).append("/libload.so")
                .append(" -lhost 127.0.0.1")
                .append(" -lport ").append(loadPort)
                .append(" -tunnel ");
        for (String t : tunnelList) {
            loadCmdStr.append(t).append(" ");
        }
        loadCmdStr.append("> /dev/null 2>&1");

        final String loadCmd = loadCmdStr.toString();
        new Thread(() -> Utility.exec(new String[]{"sh", "-c", loadCmd})).start();

        try {
            Thread.sleep(1000);
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

        command += " > /dev/null 2>&1";

        if (Utility.exec(new String[]{"sh", "-c", command}) != 0) {
            stopMe();
            return;
        }

        // Try to send the Fd through socket.
        int i = 0;
        while (i < 5) {
            if (System.sendfd(fd, getApplicationInfo().dataDir + "/sock_path") != -1) {
                mRunning = true;
                broadcastState(true);
                return;
            }

            i++;

            try {
                Thread.sleep(1000L * i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Should not get here. Must be a failure.
        stopMe();
    }

    private void broadcastState(boolean running) {
        int n = mCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onStateChanged(running);
            } catch (Exception e) {
                // ignore
            }
        }
        mCallbacks.finishBroadcast();
    }

    private static class SocksForwarder extends Thread {
        private final int listenPort;
        private final String targetHost;
        private final int targetPort;
        private final int proxyPort;
        private Selector selector;
        private ServerSocketChannel serverChannel;
        private volatile boolean running = true;

        private enum State {
            HANDSHAKE, CONNECT, FORWARDING
        }

        private static class Session {
            SelectionKey peerKey;
            boolean isRemote;
            State state = State.FORWARDING;
            ByteBuffer inBuffer = ByteBuffer.allocate(32768);
            ByteBuffer outBuffer = ByteBuffer.allocate(32768);
            boolean closed = false;

            Session(boolean isRemote) {
                this.isRemote = isRemote;
                if (isRemote) state = State.HANDSHAKE;
                outBuffer.flip();
            }
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

                        try {
                            if (key.isAcceptable()) accept();
                            else if (key.isConnectable()) finishConnect(key);
                            else if (key.isReadable()) read(key);
                            else if (key.isWritable()) write(key);
                        } catch (IOException e) {
                            close(key);
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

            SocketChannel remote = SocketChannel.open();
            remote.configureBlocking(false);
            remote.connect(new InetSocketAddress("127.0.0.1", proxyPort));

            Session clientSession = new Session(false);
            Session remoteSession = new Session(true);

            SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ, clientSession);
            SelectionKey remoteKey = remote.register(selector, SelectionKey.OP_CONNECT, remoteSession);

            clientSession.peerKey = remoteKey;
            remoteSession.peerKey = clientKey;
        }

        private void finishConnect(SelectionKey key) throws IOException {
            SocketChannel remote = (SocketChannel) key.channel();
            Session session = (Session) key.attachment();
            if (remote.finishConnect()) {
                session.state = State.HANDSHAKE;
                session.outBuffer.clear();
                session.outBuffer.put(new byte[]{0x05, 0x01, 0x00});
                session.outBuffer.flip();
                key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            }
        }

        private void read(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Session session = (Session) key.attachment();

            if (!session.isRemote && session.state != State.FORWARDING) {
                Session peerSession = (Session) session.peerKey.attachment();
                if (peerSession.state != State.FORWARDING) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                    return;
                }
            }

            int n = channel.read(session.inBuffer);
            if (n == -1) throw new IOException("EOF");

            if (session.isRemote && session.state != State.FORWARDING) {
                handleProtocol(key);
            } else {
                Session peerSession = (Session) session.peerKey.attachment();
                session.inBuffer.flip();
                if (peerSession.outBuffer.capacity() - peerSession.outBuffer.limit() >= session.inBuffer.remaining()) {
                    peerSession.outBuffer.compact();
                    peerSession.outBuffer.put(session.inBuffer);
                    peerSession.outBuffer.flip();
                    session.inBuffer.clear();
                    session.peerKey.interestOps(session.peerKey.interestOps() | SelectionKey.OP_WRITE);
                } else {
                    session.inBuffer.compact();
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                }
            }
        }

        private void handleProtocol(final SelectionKey key) throws IOException {
            final Session session = (Session) key.attachment();
            ByteBuffer buf = session.inBuffer;
            buf.flip();

            if (session.state == State.HANDSHAKE) {
                if (buf.remaining() < 2) { buf.compact(); return; }
                if (buf.get(1) != 0x00) throw new IOException("Handshake failed");
                buf.position(buf.position() + 2);
                session.state = State.CONNECT;

                final byte[] ip = InetAddress.getByName(targetHost).getAddress();
                synchronized (session.outBuffer) {
                    session.outBuffer.clear();
                    session.outBuffer.put(new byte[]{0x05, 0x01, 0x00, (byte) (ip.length == 4 ? 0x01 : 0x04)});
                    session.outBuffer.put(ip);
                    session.outBuffer.putShort((short) targetPort);
                    session.outBuffer.flip();
                }
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } else if (session.state == State.CONNECT) {
                if (buf.remaining() < 4) { buf.compact(); return; }
                if (buf.get(1) != 0x00) throw new IOException("Connect failed");
                int atyp = buf.get(3) & 0xFF;
                int needed = 4 + (atyp == 0x01 ? 4 : atyp == 0x04 ? 16 : atyp == 0x03 ? (buf.get(4) & 0xFF) + 1 : 0) + 2;
                if (buf.remaining() < needed) { buf.compact(); return; }
                buf.position(buf.position() + needed);
                session.state = State.FORWARDING;

                Session peerSession = (Session) session.peerKey.attachment();
                peerSession.outBuffer.compact();
                peerSession.outBuffer.put(buf);
                peerSession.outBuffer.flip();

                session.peerKey.interestOps(session.peerKey.interestOps() | SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
            buf.compact();
        }

        private void write(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Session session = (Session) key.attachment();

            synchronized (session.outBuffer) {
                if (session.outBuffer.hasRemaining()) {
                    channel.write(session.outBuffer);
                }
            }

            if (!session.outBuffer.hasRemaining()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                session.peerKey.interestOps(session.peerKey.interestOps() | SelectionKey.OP_READ);
            }
        }

        private void close(SelectionKey key) {
            Session session = (Session) key.attachment();
            if (session == null) {
                try { key.channel().close(); } catch (IOException ignored) {}
                key.cancel();
                return;
            }
            if (session.closed) return;
            session.closed = true;
            try { key.channel().close(); } catch (IOException ignored) {}
            key.cancel();
            if (session.peerKey != null) close(session.peerKey);
        }

        public void stopForwarder() {
            running = false;
            if (selector != null) {
                try {
                    for (SelectionKey key : selector.keys()) close(key);
                    selector.close();
                } catch (IOException ignored) {}
            }
            if (serverChannel != null) {
                try { serverChannel.close(); } catch (IOException ignored) {}
            }
        }
    }
}
