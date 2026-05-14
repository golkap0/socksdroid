package net.typeblog.socks.util;

import android.content.SharedPreferences;

import static net.typeblog.socks.util.Constants.*;

public class Profile {
    private final SharedPreferences mPref;
    private final String mName;
    private final String mPrefix;

    Profile(SharedPreferences pref, String name) {
        mPref = pref;
        mName = name;
        mPrefix = prefPrefix(name);
    }

    public String getName() {
        return mName;
    }

    public String getServer() {
        return mPref.getString(key("server"), "127.0.0.1");
    }

    public void setServer(String server) {
        mPref.edit().putString(key("server"), server).apply();
    }

    public int getPort() {
        return mPref.getInt(key("port"), 1080);
    }

    public void setPort(int port) {
        mPref.edit().putInt(key("port"), port).apply();
    }

    public boolean isUserPw() {
        return mPref.getBoolean(key("userpw"), false);
    }

    public void setIsUserpw(boolean is) {
        mPref.edit().putBoolean(key("userpw"), is).apply();
    }

    public String getUsername() {
        return mPref.getString(key("username"), "");
    }

    public void setUsername(String username) {
        mPref.edit().putString(key("username"), username).apply();
    }

    public String getPassword() {
        return mPref.getString(key("password"), "");
    }

    public void setPassword(String password) {
        mPref.edit().putString(key("password"), password).apply();
    }

    public String getRoute() {
        return mPref.getString(key("route"), ROUTE_ALL);
    }

    public void setRoute(String route) {
        mPref.edit().putString(key("route"), route).apply();
    }

    public String getDns() {
        return mPref.getString(key("dns"), "9.9.9.9");
    }

    public void setDns(String dns) {
        mPref.edit().putString(key("dns"), dns).apply();
    }

    public int getDnsPort() {
        return mPref.getInt(key("dns_port"), 9953);
    }

    public void setDnsPort(int port) {
        mPref.edit().putInt(key("dns_port"), port).apply();
    }

    public boolean isPerApp() {
        return mPref.getBoolean(key("perapp"), false);
    }

    public void setIsPerApp(boolean is) {
        mPref.edit().putBoolean(key("perapp"), is).apply();
    }

    public boolean isBypassApp() {
        return mPref.getBoolean(key("appbypass"), false);
    }

    public void setIsBypassApp(boolean is) {
        mPref.edit().putBoolean(key("appbypass"), is).apply();
    }

    public String getAppList() {
        return mPref.getString(key("applist"), "");
    }

    public void setAppList(String list) {
        mPref.edit().putString(key("applist"), list).apply();
    }

    public boolean hasIPv6() {
        return mPref.getBoolean(key("ipv6"), false);
    }

    public void setHasIPv6(boolean has) {
        mPref.edit().putBoolean(key("ipv6"), has).apply();
    }

    public boolean hasUDP() {
        return mPref.getBoolean(key("udp"), false);
    }

    public void setHasUDP(boolean has) {
        mPref.edit().putBoolean(key("udp"), has).apply();
    }

    public String getUDPGW() {
        return mPref.getString(key("udpgw"), "127.0.0.1:7300");
    }

    public void setUDPGW(String gw) {
        mPref.edit().putString(key("udpgw"), gw).apply();
    }

    public String getObfsKey() {
        return mPref.getString(key("obfs_key"), "hu``hqb`c");
    }

    public void setObfsKey(String key) {
        mPref.edit().putString(key("obfs_key"), key).apply();
    }

    public String getUpLimit() {
        return mPref.getString(key("up_limit"), "5 Mbps");
    }

    public void setUpLimit(String limit) {
        mPref.edit().putString(key("up_limit"), limit).apply();
    }

    public String getDownLimit() {
        return mPref.getString(key("down_limit"), "2 Mbps");
    }

    public void setDownLimit(String limit) {
        mPref.edit().putString(key("down_limit"), limit).apply();
    }

    public int getRecvWinConn() {
        return mPref.getInt(key("recv_win_conn"), 1048576);
    }

    public void setRecvWinConn(int win) {
        mPref.edit().putInt(key("recv_win_conn"), win).apply();
    }

    public int getRecvWin() {
        return mPref.getInt(key("recv_win"), 3145728);
    }

    public void setRecvWin(int win) {
        mPref.edit().putInt(key("recv_win"), win).apply();
    }

    public int getCoreCount() {
        return mPref.getInt(key("core_count"), 1);
    }

    public void setCoreCount(int count) {
        mPref.edit().putInt(key("core_count"), count).apply();
    }

    public String getTunnelHost() {
        return mPref.getString(key("tunnel_host"), "ssh-2.chice.me");
    }

    public void setTunnelHost(String host) {
        mPref.edit().putString(key("tunnel_host"), host).apply();
    }

    public String getTunnelUser() {
        return mPref.getString(key("tunnel_user"), "vpnstunnel-bnml0");
    }

    public void setTunnelUser(String user) {
        mPref.edit().putString(key("tunnel_user"), user).apply();
    }

    public boolean autoConnect() {
        return mPref.getBoolean(key("auto"), false);
    }

    public void setAutoConnect(boolean auto) {
        mPref.edit().putBoolean(key("auto"), auto).apply();
    }

    void delete() {
        mPref.edit()
                .remove(key("server"))
                .remove(key("port"))
                .remove(key("userpw"))
                .remove(key("username"))
                .remove(key("password"))
                .remove(key("route"))
                .remove(key("dns"))
                .remove(key("dns_port"))
                .remove(key("perapp"))
                .remove(key("appbypass"))
                .remove(key("applist"))
                .remove(key("ipv6"))
                .remove(key("udp"))
                .remove(key("udpgw"))
                .remove(key("obfs_key"))
                .remove(key("up_limit"))
                .remove(key("down_limit"))
                .remove(key("recv_win_conn"))
                .remove(key("recv_win"))
                .remove(key("core_count"))
                .remove(key("tunnel_host"))
                .remove(key("tunnel_user"))
                .remove(key("auto"))
                .apply();
    }

    private String key(String k) {
        return mPrefix + k;
    }

    private static String prefPrefix(String name) {
        return name.replace("_", "__").replace(" ", "_");
    }
}
