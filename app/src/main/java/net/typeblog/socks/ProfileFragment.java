package net.typeblog.socks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.ListPreference;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;
import net.typeblog.socks.util.Utility;

import java.util.Locale;

import static net.typeblog.socks.util.Constants.*;

public class ProfileFragment extends PreferenceFragment implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener,
        CompoundButton.OnCheckedChangeListener {
    private ProfileManager mManager;
    private Profile mProfile;

    private Switch mSwitch;
    private boolean mRunning = false;
    private boolean mStarting = false, mStopping = false;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName p1, IBinder binder) {
            mBinder = IVpnService.Stub.asInterface(binder);

            try {
                mRunning = mBinder.isRunning();
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (mRunning) {
                updateState();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName p1) {
            mBinder = null;
        }
    };
    private final Runnable mStateRunnable = new Runnable() {
        @Override
        public void run() {
            updateState();
            if (mSwitch != null) {
                mSwitch.postDelayed(this, 1000);
            }
        }
    };
    private IVpnService mBinder;
    private boolean mMonitoringPort = false;
    private final Runnable mMonitoringRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mMonitoringPort) return;

            new Thread(() -> {
                final boolean isOpen = Utility.isPortOpen(1080);

                Activity activity = getActivity();
                if (activity == null) return;

                activity.runOnUiThread(() -> {
                    if (!mMonitoringPort) return;

                    if (isOpen) {
                        mMonitoringPort = false;
                        if (mPrefStatus != null) mPrefStatus.setSummary(R.string.status_connected);
                        if (getActivity() != null) Toast.makeText(getActivity(), R.string.status_connected, Toast.LENGTH_SHORT).show();
                    } else {
                        if (mPrefStatus != null) mPrefStatus.setSummary(R.string.status_disconnected);
                        if (mSwitch != null) {
                            mSwitch.postDelayed(mMonitoringRunnable, 1000);
                        }
                    }
                });
            }).start();
        }
    };

    private Preference mPrefStatus;
    private ListPreference mPrefProfile, mPrefRoutes;
    private EditTextPreference mPrefServer, mPrefPort, mPrefUsername, mPrefPassword,
            mPrefDns, mPrefDnsPort, mPrefAppList, mPrefUDPGW,
            mPrefTunnelHost, mPrefTunnelUser,
            mPrefObfsKey, mPrefUpLimit, mPrefDownLimit, mPrefRecvWinConn, mPrefRecvWin, mPrefCoreCount;
    private CheckBoxPreference mPrefUserpw, mPrefPerApp, mPrefAppBypass, mPrefIPv6, mPrefUDP, mPrefAuto;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);
        setHasOptionsMenu(true);
        mManager = new ProfileManager(getActivity().getApplicationContext());
        initPreferences();
        reload();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.main, menu);

        MenuItem s = menu.findItem(R.id.switch_main);
        mSwitch = s.getActionView().findViewById(R.id.switch_action_button);
        mSwitch.setOnCheckedChangeListener(this);
        mSwitch.postDelayed(mStateRunnable, 1000);
        checkState();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.prof_add) {
            addProfile();
            return true;
        } else if (id == R.id.prof_del) {
            removeProfile();
            return true;
        } else if (id == R.id.prof_import) {
            importProfile();
            return true;
        } else if (id == R.id.prof_export) {
            exportProfile();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference p) {
        // TODO: Implement this method
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference p, Object newValue) {
        if (p == mPrefProfile) {
            String name = newValue.toString();
            mProfile = mManager.getProfile(name);
            mManager.switchDefault(name);
            reload();
            return true;
        } else if (p == mPrefServer) {
            mProfile.setServer(newValue.toString());
            resetTextN(mPrefServer, newValue);
            return true;
        } else if (p == mPrefPort) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;

            mProfile.setPort(Integer.parseInt(newValue.toString()));
            resetTextN(mPrefPort, newValue);
            return true;
        } else if (p == mPrefUserpw) {
            mProfile.setIsUserpw(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefUsername) {
            mProfile.setUsername(newValue.toString());
            resetTextN(mPrefUsername, newValue);
            return true;
        } else if (p == mPrefPassword) {
            mProfile.setPassword(newValue.toString());
            resetTextN(mPrefPassword, newValue);
            return true;
        } else if (p == mPrefRoutes) {
            mProfile.setRoute(newValue.toString());
            resetListN(mPrefRoutes, newValue);
            return true;
        } else if (p == mPrefDns) {
            mProfile.setDns(newValue.toString());
            resetTextN(mPrefDns, newValue);
            return true;
        } else if (p == mPrefDnsPort) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;

            mProfile.setDnsPort(Integer.parseInt(newValue.toString()));
            resetTextN(mPrefDnsPort, newValue);
            return true;
        } else if (p == mPrefPerApp) {
            mProfile.setIsPerApp(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefAppBypass) {
            mProfile.setIsBypassApp(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefAppList) {
            mProfile.setAppList(newValue.toString());
            return true;
        } else if (p == mPrefIPv6) {
            mProfile.setHasIPv6(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefUDP) {
            mProfile.setHasUDP(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefUDPGW) {
            mProfile.setUDPGW(newValue.toString());
            resetTextN(mPrefUDPGW, newValue);
            return true;
        } else if (p == mPrefTunnelHost) {
            mProfile.setTunnelHost(newValue.toString());
            resetTextN(mPrefTunnelHost, newValue);
            return true;
        } else if (p == mPrefTunnelUser) {
            mProfile.setTunnelUser(newValue.toString());
            resetTextN(mPrefTunnelUser, newValue);
            return true;
        } else if (p == mPrefObfsKey) {
            mProfile.setObfsKey(newValue.toString());
            resetTextN(mPrefObfsKey, newValue);
            return true;
        } else if (p == mPrefUpLimit) {
            mProfile.setUpLimit(newValue.toString());
            resetTextN(mPrefUpLimit, newValue);
            return true;
        } else if (p == mPrefDownLimit) {
            mProfile.setDownLimit(newValue.toString());
            resetTextN(mPrefDownLimit, newValue);
            return true;
        } else if (p == mPrefRecvWinConn) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;
            mProfile.setRecvWinConn(Integer.parseInt(newValue.toString()));
            resetTextN(mPrefRecvWinConn, newValue);
            return true;
        } else if (p == mPrefRecvWin) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;
            mProfile.setRecvWin(Integer.parseInt(newValue.toString()));
            resetTextN(mPrefRecvWin, newValue);
            return true;
        } else if (p == mPrefCoreCount) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;
            int count = Integer.parseInt(newValue.toString());
            if (count < 1) count = 1;
            if (count > 10) count = 10;
            mProfile.setCoreCount(count);
            resetTextN(mPrefCoreCount, count);
            return true;
        } else if (p == mPrefAuto) {
            mProfile.setAutoConnect(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton p1, boolean checked) {
        if (checked) {
            startVpn();
            mMonitoringPort = true;
            if (mSwitch != null) {
                mSwitch.postDelayed(mMonitoringRunnable, 1000);
            }
        } else {
            stopVpn();
            mMonitoringPort = false;
            if (mSwitch != null) {
                mSwitch.removeCallbacks(mMonitoringRunnable);
            }
            if (mPrefStatus != null) mPrefStatus.setSummary(R.string.status_disconnected);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mSwitch != null) {
            mSwitch.postDelayed(mStateRunnable, 1000);
            if (mMonitoringPort) {
                mSwitch.postDelayed(mMonitoringRunnable, 1000);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSwitch != null) {
            mSwitch.removeCallbacks(mStateRunnable);
            mSwitch.removeCallbacks(mMonitoringRunnable);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBinder != null) {
            Activity activity = getActivity();
            if (activity != null) {
                activity.unbindService(mConnection);
            }
            mBinder = null;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK) {
            Utility.startVpn(getActivity(), mProfile);
            checkState();
        }
    }

    private void initPreferences() {
        mPrefStatus = findPreference("vpn_status");
        mPrefProfile = (ListPreference) findPreference(PREF_PROFILE);
        mPrefServer = (EditTextPreference) findPreference(PREF_SERVER_IP);
        mPrefPort = (EditTextPreference) findPreference(PREF_SERVER_PORT);
        mPrefUserpw = (CheckBoxPreference) findPreference(PREF_AUTH_USERPW);
        mPrefUsername = (EditTextPreference) findPreference(PREF_AUTH_USERNAME);
        mPrefPassword = (EditTextPreference) findPreference(PREF_AUTH_PASSWORD);
        mPrefRoutes = (ListPreference) findPreference(PREF_ADV_ROUTE);
        mPrefDns = (EditTextPreference) findPreference(PREF_ADV_DNS);
        mPrefDnsPort = (EditTextPreference) findPreference(PREF_ADV_DNS_PORT);
        mPrefPerApp = (CheckBoxPreference) findPreference(PREF_ADV_PER_APP);
        mPrefAppBypass = (CheckBoxPreference) findPreference(PREF_ADV_APP_BYPASS);
        mPrefAppList = (EditTextPreference) findPreference(PREF_ADV_APP_LIST);
        mPrefIPv6 = (CheckBoxPreference) findPreference(PREF_IPV6_PROXY);
        mPrefUDP = (CheckBoxPreference) findPreference(PREF_UDP_PROXY);
        mPrefUDPGW = (EditTextPreference) findPreference(PREF_UDP_GW);
        mPrefTunnelHost = (EditTextPreference) findPreference(PREF_TUNNEL_HOST);
        mPrefTunnelUser = (EditTextPreference) findPreference(PREF_TUNNEL_USER);
        mPrefObfsKey = (EditTextPreference) findPreference(PREF_OBFS_KEY);
        mPrefUpLimit = (EditTextPreference) findPreference(PREF_UP_LIMIT);
        mPrefDownLimit = (EditTextPreference) findPreference(PREF_DOWN_LIMIT);
        mPrefRecvWinConn = (EditTextPreference) findPreference(PREF_RECV_WIN_CONN);
        mPrefRecvWin = (EditTextPreference) findPreference(PREF_RECV_WIN);
        mPrefCoreCount = (EditTextPreference) findPreference(PREF_CORE_COUNT);
        mPrefAuto = (CheckBoxPreference) findPreference(PREF_ADV_AUTO_CONNECT);

        mPrefProfile.setOnPreferenceChangeListener(this);
        mPrefServer.setOnPreferenceChangeListener(this);
        mPrefPort.setOnPreferenceChangeListener(this);
        mPrefUserpw.setOnPreferenceChangeListener(this);
        mPrefUsername.setOnPreferenceChangeListener(this);
        mPrefPassword.setOnPreferenceChangeListener(this);
        mPrefRoutes.setOnPreferenceChangeListener(this);
        mPrefDns.setOnPreferenceChangeListener(this);
        mPrefDnsPort.setOnPreferenceChangeListener(this);
        mPrefPerApp.setOnPreferenceChangeListener(this);
        mPrefAppBypass.setOnPreferenceChangeListener(this);
        mPrefAppList.setOnPreferenceChangeListener(this);
        mPrefIPv6.setOnPreferenceChangeListener(this);
        mPrefUDP.setOnPreferenceChangeListener(this);
        mPrefUDPGW.setOnPreferenceChangeListener(this);
        mPrefTunnelHost.setOnPreferenceChangeListener(this);
        mPrefTunnelUser.setOnPreferenceChangeListener(this);
        mPrefObfsKey.setOnPreferenceChangeListener(this);
        mPrefUpLimit.setOnPreferenceChangeListener(this);
        mPrefDownLimit.setOnPreferenceChangeListener(this);
        mPrefRecvWinConn.setOnPreferenceChangeListener(this);
        mPrefRecvWin.setOnPreferenceChangeListener(this);
        mPrefCoreCount.setOnPreferenceChangeListener(this);
        mPrefAuto.setOnPreferenceChangeListener(this);
    }

    private void reload() {
        if (mProfile == null) {
            mProfile = mManager.getDefault();
        }

        mPrefProfile.setEntries(mManager.getProfiles());
        mPrefProfile.setEntryValues(mManager.getProfiles());
        mPrefProfile.setValue(mProfile.getName());
        mPrefRoutes.setValue(mProfile.getRoute());
        resetList(mPrefProfile, mPrefRoutes);

        mPrefUserpw.setChecked(mProfile.isUserPw());
        mPrefPerApp.setChecked(mProfile.isPerApp());
        mPrefAppBypass.setChecked(mProfile.isBypassApp());
        mPrefIPv6.setChecked(mProfile.hasIPv6());
        mPrefUDP.setChecked(mProfile.hasUDP());
        mPrefAuto.setChecked(mProfile.autoConnect());

        mPrefServer.setText(mProfile.getServer());
        mPrefPort.setText(String.valueOf(mProfile.getPort()));
        mPrefUsername.setText(mProfile.getUsername());
        mPrefPassword.setText(mProfile.getPassword());
        mPrefDns.setText(mProfile.getDns());
        mPrefDnsPort.setText(String.valueOf(mProfile.getDnsPort()));
        mPrefUDPGW.setText(mProfile.getUDPGW());
        mPrefTunnelHost.setText(mProfile.getTunnelHost());
        mPrefTunnelUser.setText(mProfile.getTunnelUser());
        mPrefObfsKey.setText(mProfile.getObfsKey());
        mPrefUpLimit.setText(mProfile.getUpLimit());
        mPrefDownLimit.setText(mProfile.getDownLimit());
        mPrefRecvWinConn.setText(String.valueOf(mProfile.getRecvWinConn()));
        mPrefRecvWin.setText(String.valueOf(mProfile.getRecvWin()));
        mPrefCoreCount.setText(String.valueOf(mProfile.getCoreCount()));
        resetText(mPrefServer, mPrefPort, mPrefUsername, mPrefPassword, mPrefDns, mPrefDnsPort, mPrefUDPGW,
                mPrefTunnelHost, mPrefTunnelUser,
                mPrefObfsKey, mPrefUpLimit, mPrefDownLimit, mPrefRecvWinConn, mPrefRecvWin, mPrefCoreCount);

        mPrefAppList.setText(mProfile.getAppList());
    }

    private void resetList(ListPreference... pref) {
        for (ListPreference p : pref)
            p.setSummary(p.getEntry());
    }

    private void resetListN(ListPreference pref, Object newValue) {
        pref.setSummary(newValue.toString());
    }

    private void resetText(EditTextPreference... pref) {
        for (EditTextPreference p : pref) {
            if ((p.getEditText().getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                p.setSummary(p.getText());
            } else {
                if (p.getText().length() > 0)
                    p.setSummary(String.format(Locale.US,
                            String.format(Locale.US, "%%0%dd", p.getText().length()), 0)
                            .replace("0", "*"));
                else
                    p.setSummary("");
            }
        }
    }

    private void resetTextN(EditTextPreference pref, Object newValue) {
        if ((pref.getEditText().getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            pref.setSummary(newValue.toString());
        } else {
            String text = newValue.toString();
            if (text.length() > 0)
                pref.setSummary(String.format(Locale.US,
                        String.format(Locale.US, "%%0%dd", text.length()), 0)
                        .replace("0", "*"));
            else
                pref.setSummary("");
        }
    }

    private void addProfile() {
        final EditText e = new EditText(getActivity());
        e.setSingleLine(true);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.prof_add)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = e.getText().toString().trim();

                    if (!TextUtils.isEmpty(name)) {
                        Profile p = mManager.addProfile(name);

                        if (p != null) {
                            mProfile = p;
                            reload();
                            return;
                        }
                    }

                    Toast.makeText(getActivity(),
                            String.format(getString(R.string.err_add_prof), name),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> {

                })
                .create().show();
    }

    private void importProfile() {
        final EditText e = new EditText(getActivity());
        e.setHint("zivpn://server@udpauth");

        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClipDescription().hasMimeType("text/plain")) {
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                ClipData.Item item = clipData.getItemAt(0);
                CharSequence text = item.getText();
                if (text != null) {
                    String content = text.toString();
                    if (content.contains("zivpn://")) {
                        e.setText(content);
                    }
                }
            }
        }

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.prof_import)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String input = e.getText().toString().trim();
                    if (TextUtils.isEmpty(input)) return;

                    String[] lines = input.split("\\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("zivpn://")) {
                            String content = line.substring(8);
                            String[] parts = content.split("@");
                            if (parts.length == 2) {
                                String server = parts[0];
                                String auth = parts[1];
                                Profile p = mManager.addProfile(server);
                                if (p != null) {
                                    p.setTunnelHost(server);
                                    p.setTunnelUser(auth);
                                    p.setServer("127.0.0.1"); // Load balancer address
                                    p.setPort(7777); // Load balancer port
                                    mProfile = p;
                                }
                            }
                        }
                    }
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create().show();
    }

    private void exportProfile() {
        String export = String.format("zivpn://%s@%s", mProfile.getTunnelHost(), mProfile.getTunnelUser());
        final EditText e = new EditText(getActivity());
        e.setText(export);
        e.setKeyListener(null); // Make it read-only but selectable

        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("VPN Profile", export);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(getActivity(), R.string.prof_export_copied, Toast.LENGTH_SHORT).show();

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.prof_export)
                .setView(e)
                .setPositiveButton(android.R.string.ok, null)
                .create().show();
    }

    private void removeProfile() {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.prof_del)
                .setMessage(String.format(getString(R.string.prof_del_confirm), mProfile.getName()))
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (!mManager.removeProfile(mProfile.getName())) {
                        Toast.makeText(getActivity(),
                                getString(R.string.err_del_prof, mProfile.getName()),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        mProfile = mManager.getDefault();
                        reload();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> {

                })
                .create().show();
    }

    private void checkState() {
        mRunning = false;
        mSwitch.setEnabled(false);
        mSwitch.setOnCheckedChangeListener(null);

        if (mBinder == null) {
            getActivity().bindService(new Intent(getActivity(), SocksVpnService.class), mConnection, 0);
        }
    }

    private void updateState() {
        if (mBinder == null) {
            mRunning = false;
        } else {
            try {
                mRunning = mBinder.isRunning();
            } catch (Exception e) {
                mRunning = false;
            }
        }

        mSwitch.setChecked(mRunning);

        if ((!mStarting && !mStopping) || (mStarting && mRunning) || (mStopping && !mRunning)) {
            mSwitch.setEnabled(true);
        }

        if (mStarting && mRunning) {
            mStarting = false;
        }

        if (mStopping && !mRunning) {
            mStopping = false;
        }

        mSwitch.setOnCheckedChangeListener(ProfileFragment.this);
    }

    private void startVpn() {
        mStarting = true;
        Intent i = VpnService.prepare(getActivity());

        if (i != null) {
            startActivityForResult(i, 0);
        } else {
            onActivityResult(0, Activity.RESULT_OK, null);
        }
    }

    private void stopVpn() {
        if (mBinder == null)
            return;

        mStopping = true;

        try {
            mBinder.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }

        mBinder = null;

        getActivity().unbindService(mConnection);
        checkState();
    }
}
