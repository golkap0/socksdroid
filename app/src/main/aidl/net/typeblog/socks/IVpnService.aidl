package net.typeblog.socks;

import net.typeblog.socks.IVpnServiceCallback;

interface IVpnService
{
	boolean isRunning();
	void stop();
	String getLogs();
	void clearLogs();
	void setLoggingEnabled(boolean enabled);
	boolean isLoggingEnabled();
	void registerCallback(IVpnServiceCallback cb);
	void unregisterCallback(IVpnServiceCallback cb);
}
