package net.typeblog.socks.util;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.List;

import net.typeblog.socks.R;
import net.typeblog.socks.SocksVpnService;
import static net.typeblog.socks.util.Constants.*;

public class Utility {

    public interface OnLogListener {
        void onLog(String line);
    }

    public static int exec(String cmd) {
        return exec(cmd, null);
    }

    public static int exec(String cmd, OnLogListener listener) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            if (listener != null) {
                final Process fp = p;
                Thread stdoutThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fp.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            listener.onLog(line);
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                stdoutThread.setDaemon(true);
                stdoutThread.start();

                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fp.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            listener.onLog(line);
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                stderrThread.setDaemon(true);
                stderrThread.start();
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) {
                try { p.getInputStream().close(); } catch (Exception ignored) {}
                try { p.getErrorStream().close(); } catch (Exception ignored) {}
                try { p.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
    }

    public static int exec(String[] cmd) {
        return exec(cmd, null);
    }

    public static int exec(String[] cmd, OnLogListener listener) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            if (listener != null) {
                final Process fp = p;
                Thread stdoutThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fp.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            listener.onLog(line);
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                stdoutThread.setDaemon(true);
                stdoutThread.start();

                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fp.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            listener.onLog(line);
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                stderrThread.setDaemon(true);
                stderrThread.start();
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) {
                try { p.getInputStream().close(); } catch (Exception ignored) {}
                try { p.getErrorStream().close(); } catch (Exception ignored) {}
                try { p.getOutputStream().close(); } catch (Exception ignored) {}
            }
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
            file.delete();
        } catch (Exception e) {
            // Ignore
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
            f.delete();
        }

        try {
            OutputStream out = new FileOutputStream(f);
            out.write(conf.getBytes());
            out.flush();
            out.close();
        } catch (Exception e) {
            // Ignore
        }

        File cache = new File(context.getFilesDir() + "/pdnsd.cache");

        if (!cache.exists()) {
            try {
                cache.createNewFile();
            } catch (Exception e) {
                // Ignore
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
