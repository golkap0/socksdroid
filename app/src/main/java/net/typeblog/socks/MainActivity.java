package net.typeblog.socks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import androidx.drawerlayout.widget.DrawerLayout;

public class MainActivity extends Activity {
    private DrawerLayout mDrawerLayout;
    private IVpnService mBinder;
    private boolean mBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IVpnService.Stub.asInterface(service);
            mBound = true;
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

        mDrawerLayout = findViewById(R.id.drawer_layout);

        getFragmentManager().beginTransaction().replace(R.id.content_frame, new ProfileFragment()).commit();

        bindService(new Intent(this, SocksVpnService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }
}
