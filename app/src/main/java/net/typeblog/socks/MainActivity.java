package net.typeblog.socks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private IVpnService mBinder;
    private boolean mBound = false;
    private boolean mBinding = false;
    private final IVpnServiceCallback mCallback = new VpnServiceCallbackStub();

    private static class VpnServiceCallbackStub extends IVpnServiceCallback.Stub {
        @Override
        public void onStateChanged(boolean running) {
            // Handled in ProfileFragment
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IVpnService.Stub.asInterface(service);
            mBound = true;
            try {
                mBinder.registerCallback(mCallback);
            } catch (Exception e) {
                // Ignore
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinder = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getFragmentManager().beginTransaction().replace(R.id.content_frame, new ProfileFragment()).commit();

        mBinding = bindService(new Intent(this, SocksVpnService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBound) {
            if (mBinder != null) {
                try {
                    mBinder.unregisterCallback(mCallback);
                } catch (Exception e) {
                    // Ignore
                }
            }
            mBound = false;
        }

        if (mBinding) {
            unbindService(mConnection);
            mBinding = false;
        }
    }
}
