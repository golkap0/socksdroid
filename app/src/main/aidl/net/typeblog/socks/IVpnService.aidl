package net.typeblog.socks;

import net.typeblog.socks.IVpnServiceCallback;

interface IVpnService
{
	boolean isRunning();
	void stop();
	void registerCallback(IVpnServiceCallback cb);
	void unregisterCallback(IVpnServiceCallback cb);
}
