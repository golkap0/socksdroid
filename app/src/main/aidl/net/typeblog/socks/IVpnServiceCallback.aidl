package net.typeblog.socks;

interface IVpnServiceCallback
{
	void onStateChanged(boolean running);
	void onLogAdded(String line);
}
