package net.typeblog.socks.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import net.typeblog.socks.R;
import net.typeblog.socks.SocksVpnService;
import static net.typeblog.socks.util.Constants.*;

public class Utility {
    private static final String TAG = Utility.class.getSimpleName();

    private static class StreamGobbler extends Thread {
        private final InputStream is;

        StreamGobbler(InputStream is) {
            this.is = is;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                while (!isInterrupted() && br.readLine() != null);
            } catch (Exception ignored) {
            } finally {
                if (isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static int execInternal(Object cmd, long timeoutMs) throws IOException {
        Process p;
        if (cmd instanceof String) {
            p = Runtime.getRuntime().exec((String) cmd);
        } else if (cmd instanceof String[]) {
            p = Runtime.getRuntime().exec((String[]) cmd);
        } else {
            throw new IllegalArgumentException("cmd must be String or String[]");
        }

        StreamGobbler stdout = new StreamGobbler(p.getInputStream());
        StreamGobbler stderr = new StreamGobbler(p.getErrorStream());
        stdout.start();
        stderr.start();

        int ret = -1;
        long start = java.lang.System.currentTimeMillis();

        while (true) {
            try {
                ret = p.exitValue();
                break;
            } catch (IllegalThreadStateException e) {
                if (timeoutMs > 0 && java.lang.System.currentTimeMillis() - start > timeoutMs) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        p.destroyForcibly();
                    } else {
                        p.destroy();
                    }
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        try {
            stdout.join(1000);
            if (stdout.isAlive()) stdout.interrupt();
            stderr.join(1000);
            if (stderr.isAlive()) stderr.interrupt();
        } catch (InterruptedException e) {
            stdout.interrupt();
            stderr.interrupt();
            Thread.currentThread().interrupt();
        }

        p.getInputStream().close();
        p.getOutputStream().close();
        p.getErrorStream().close();
        return ret;
    }

    public static int exec(String cmd) {
        return exec(cmd, 30000);
    }

    public static int exec(String cmd, long timeoutMs) {
        try {
            return execInternal(cmd, timeoutMs);
        } catch (Exception e) {
            return -1;
        }
    }

    public static int exec(String[] cmd) {
        return exec(cmd, 30000);
    }

    public static int exec(String[] cmd, long timeoutMs) {
        try {
            return execInternal(cmd, timeoutMs);
        } catch (Exception e) {
            return -1;
        }
    }

    public static void killPidFile(String f) {
        File file = new File(f);

        if (!file.exists()) {
            return;
        }

        StringBuilder str = new StringBuilder();

        try (InputStream i = new FileInputStream(file)) {
            byte[] buf = new byte[512];
            int len;

            while ((len = i.read(buf, 0, 512)) > 0) {
                str.append(new String(buf, 0, len));
            }
        } catch (Exception e) {
            return;
        }

        try {
            int pid = Integer.parseInt(str.toString().trim().replace("\n", ""));
            Process p = Runtime.getRuntime().exec(new String[]{"kill", String.valueOf(pid)});
            p.waitFor();
            if (p.exitValue() != 0) {
                // If standard kill failed, try kill -9
                Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)}).waitFor();
            }
            if(!file.delete())
                Log.w(TAG, "failed to delete pidfile");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String join(List<String> list, String separator) {
        if (list.isEmpty()) return "";

        StringBuilder ret = new StringBuilder();

        for (String s : list) {
            ret.append(s).append(separator);
        }

        return ret.substring(0, ret.length() - separator.length());
    }

    public static void makePdnsdConf(Context context, String dns, int port) {
        String conf = context.getString(R.string.pdnsd_conf)
                .replace("{DIR}", context.getFilesDir().toString())
                .replace("{IP}", dns)
                .replace("{PORT}", Integer.toString(port));

        File f = new File(context.getFilesDir() + "/pdnsd.conf");

        if (f.exists()) {
            if(!f.delete())
                Log.w(TAG, "failed to delete pdnsd.conf");
        }

        try {
            OutputStream out = new FileOutputStream(f);
            out.write(conf.getBytes());
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        File cache = new File(context.getFilesDir() + "/pdnsd.cache");

        if (!cache.exists()) {
            try {
                if(!cache.createNewFile())
                    Log.w(TAG, "failed to create pdnsd.cache");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void startVpn(Context context, Profile profile) {
        Intent i = new Intent(context, SocksVpnService.class)
                .putExtra(INTENT_NAME, profile.getName())
                .putExtra(INTENT_SERVER, profile.getServer())
                .putExtra(INTENT_PORT, profile.getPort())
                .putExtra(INTENT_ROUTE, profile.getRoute())
                .putExtra(INTENT_DNS, profile.getDns())
                .putExtra(INTENT_DNS_PORT, profile.getDnsPort())
                .putExtra(INTENT_PER_APP, profile.isPerApp())
                .putExtra(INTENT_IPV6_PROXY, profile.hasIPv6())
                .putExtra(INTENT_OBFS_KEY, profile.getObfsKey())
                .putExtra(INTENT_UP_LIMIT, profile.getUpLimit())
                .putExtra(INTENT_DOWN_LIMIT, profile.getDownLimit())
                .putExtra(INTENT_RECV_WIN_CONN, profile.getRecvWinConn())
                .putExtra(INTENT_RECV_WIN, profile.getRecvWin())
                .putExtra(INTENT_CORE_COUNT, profile.getCoreCount())
                .putExtra(INTENT_TUNNEL_HOST, profile.getTunnelHost())
                .putExtra(INTENT_TUNNEL_USER, profile.getTunnelUser());

        if (profile.isUserPw()) {
            i.putExtra(INTENT_USERNAME, profile.getUsername())
                    .putExtra(INTENT_PASSWORD, profile.getPassword());
        }

        if (profile.isPerApp()) {
            i.putExtra(INTENT_APP_BYPASS, profile.isBypassApp())
                    .putExtra(INTENT_APP_LIST, profile.getAppList().split("\n"));
        }

        if (profile.hasUDP()) {
            i.putExtra(INTENT_UDP_GW, profile.getUDPGW());
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }
}
