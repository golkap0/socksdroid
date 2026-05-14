package net.typeblog.socks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import androidx.drawerlayout.widget.DrawerLayout;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {
    private DrawerLayout mDrawerLayout;
    private TextView mTvLogs;
    private Button mBtnClear;
    private Switch mSwitchLog;
    private IVpnService mBinder;
    private boolean mBound = false;
    private boolean mBinding = false;
    private final Handler mHandler = new Handler();
    private final IVpnServiceCallback mCallback = new VpnServiceCallbackStub(this);

    private static class VpnServiceCallbackStub extends IVpnServiceCallback.Stub {
        private final WeakReference<MainActivity> mActivity;

        VpnServiceCallbackStub(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void onStateChanged(boolean running) {
            // Handled in ProfileFragment
        }

        @Override
        public void onLogAdded(String line) {
            MainActivity activity = mActivity.get();
            if (activity != null) {
                activity.mHandler.post(() -> {
                    if (activity.mTvLogs != null) {
                        activity.mTvLogs.append(line + "\n");
                    }
                });
            }
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IVpnService.Stub.asInterface(service);
            mBound = true;
            try {
                mBinder.registerCallback(mCallback);
                String logs = mBinder.getLogs();
                if (logs != null) {
                    mTvLogs.setText(logs);
                }
            } catch (Exception e) {
                // Ignore
            }
            updateLogControls();
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
        mTvLogs = findViewById(R.id.tv_logs);
        mBtnClear = findViewById(R.id.btn_clear_log);
        mSwitchLog = findViewById(R.id.switch_log);

        mBtnClear.setOnClickListener(v -> {
            if (mBound && mBinder != null) {
                try {
                    mBinder.clearLogs();
                    mTvLogs.setText("");
                } catch (Exception e) {
                    // Ignore
                }
            }
        });

        mSwitchLog.setOnCheckedChangeListener((v, checked) -> {
            if (mBound && mBinder != null) {
                try {
                    mBinder.setLoggingEnabled(checked);
                } catch (Exception e) {
                    // Ignore
                }
            }
        });

        getFragmentManager().beginTransaction().replace(R.id.content_frame, new ProfileFragment()).commit();

        mBinding = bindService(new Intent(this, SocksVpnService.class), mConnection, Context.BIND_AUTO_CREATE);
    }


    private void updateLogControls() {
        if (mBound && mBinder != null) {
            try {
                mSwitchLog.setOnCheckedChangeListener(null);
                mSwitchLog.setChecked(mBinder.isLoggingEnabled());
                mSwitchLog.setOnCheckedChangeListener((v, checked) -> {
                    if (mBound && mBinder != null) {
                        try {
                            mBinder.setLoggingEnabled(checked);
                        } catch (Exception e) {
                            // Ignore
                        }
                    }
                });
            } catch (Exception e) {
                // Ignore
            }
        }
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
