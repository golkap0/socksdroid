package net.typeblog.socks.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SocksForwarder implements Runnable {
    private final String mDnsHost;
    private final int mDnsPort;
    private final String mSocksHost;
    private final int mSocksPort;
    private final int mListenPort;
    private volatile boolean mRunning = true;
    private ServerSocket mServerSocket;
    private final ExecutorService mExecutor;

    public SocksForwarder(String dnsHost, int dnsPort, String socksHost, int socksPort, int listenPort) {
        mDnsHost = dnsHost;
        mDnsPort = dnsPort;
        mSocksHost = socksHost;
        mSocksPort = socksPort;
        mListenPort = listenPort;
        mExecutor = new ThreadPoolExecutor(0, 4, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        mExecutor.shutdownNow();
    }

    @Override
    public void run() {
        try {
            mServerSocket = new ServerSocket(mListenPort, 50, InetAddress.getByName("127.0.0.1"));
            while (mRunning) {
                Socket client = mServerSocket.accept();
                mExecutor.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            // Server socket closed or error
        }
    }

    private void handleClient(Socket client) {
        try (Socket socks = new Socket()) {
            socks.connect(new InetSocketAddress(mSocksHost, mSocksPort), 5000);
            socks.setTcpNoDelay(true);

            InputStream socksIn = socks.getInputStream();
            OutputStream socksOut = socks.getOutputStream();

            // SOCKS5 greeting
            socksOut.write(new byte[]{0x05, 0x01, 0x00});
            socksOut.flush();

            byte[] response = new byte[2];
            if (readFully(socksIn, response) != 2 || response[0] != 0x05 || response[1] != 0x00) {
                client.close();
                return;
            }

            // SOCKS5 connect
            InetAddress dnsAddr = InetAddress.getByName(mDnsHost);
            byte[] dnsIp = dnsAddr.getAddress();
            byte atyp = (byte) (dnsIp.length == 4 ? 0x01 : 0x04);

            byte[] request = new byte[6 + dnsIp.length];
            request[0] = 0x05;
            request[1] = 0x01; // CONNECT
            request[2] = 0x00;
            request[3] = atyp;
            System.arraycopy(dnsIp, 0, request, 4, dnsIp.length);
            request[4 + dnsIp.length] = (byte) ((mDnsPort >> 8) & 0xFF);
            request[5 + dnsIp.length] = (byte) (mDnsPort & 0xFF);

            socksOut.write(request);
            socksOut.flush();

            response = new byte[10]; // Assuming IPv4 response
            if (readFully(socksIn, response) != 10 || response[1] != 0x00) {
                client.close();
                return;
            }

            // Forward data
            Thread t1 = new Thread(() -> pipe(client, socks));
            Thread t2 = new Thread(() -> pipe(socks, client));
            t1.start();
            t2.start();

            t1.join();
            t2.join();
        } catch (Exception e) {
            // Handle
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r == -1) break;
            read += r;
        }
        return read;
    }

    private void pipe(Socket src, Socket dst) {
        try {
            InputStream in = src.getInputStream();
            OutputStream out = dst.getOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                out.flush();
            }
        } catch (IOException e) {
            // Done
        } finally {
            try {
                dst.shutdownOutput();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
