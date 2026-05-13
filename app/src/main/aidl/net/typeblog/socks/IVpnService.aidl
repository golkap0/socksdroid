package net.typeblog.socks;

interface IVpnService
{
	boolean isRunning();
	void stop();
	String getLogs();
	void clearLogs();
	void setLoggingEnabled(boolean enabled);
	boolean isLoggingEnabled();
}
