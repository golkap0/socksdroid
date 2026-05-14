package net.typeblog.socks.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import net.typeblog.socks.R;
import static net.typeblog.socks.util.Constants.*;

public class ProfileManager {

    private final SharedPreferences mPref;
    private final Context mContext;

    public ProfileManager(Context context) {
        mContext = context;
        mPref = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private List<String> getProfileList() {
        List<String> mProfiles = new ArrayList<>();
        mProfiles.add(mContext.getString(R.string.prof_default));

        String[] profiles = mPref.getString(PREF_PROFILE, "").split("\n");

        for (String p : profiles) {
            if (!TextUtils.isEmpty(p)) {
                mProfiles.add(p);
            }
        }
        return mProfiles;
    }

    public String[] getProfiles() {
        return getProfileList().toArray(new String[0]);
    }

    public Profile getProfile(String name) {
        if (!getProfileList().contains(name)) {
            return null;
        } else {
            return new Profile(mPref, name);
        }
    }

    public Profile getDefault() {
        return new Profile(mPref, mPref.getString(PREF_LAST_PROFILE, getProfileList().get(0)));
    }

    public void switchDefault(String name) {
        if (getProfileList().contains(name))
            mPref.edit().putString(PREF_LAST_PROFILE, name).apply();
    }

    public Profile addProfile(String name) {
        List<String> mProfiles = getProfileList();
        if (mProfiles.contains(name)) {
            return null;
        } else {
            mProfiles.add(name);
            // The first profile is the default one (from R.string.prof_default)
            // We only want to save user-created profiles to PREF_PROFILE
            List<String> toSave = new ArrayList<>(mProfiles);
            toSave.remove(0);

            mPref.edit().putString(PREF_PROFILE, Utility.join(toSave, "\n"))
                    .putString(PREF_LAST_PROFILE, name).apply();
            return getProfile(name);
        }
    }

    public boolean removeProfile(String name) {
        List<String> mProfiles = getProfileList();
        // Index 0 is always the hardcoded default profile
        if (name.equals(mProfiles.get(0)) || !mProfiles.contains(name)) {
            return false;
        }

        new Profile(mPref, name).delete();

        mProfiles.remove(name);
        mProfiles.remove(0); // Remove the hardcoded default before saving

        mPref.edit().putString(PREF_PROFILE, Utility.join(mProfiles, "\n"))
                .remove(PREF_LAST_PROFILE).apply();

        return true;
    }
}
