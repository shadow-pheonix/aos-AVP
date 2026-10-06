// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video;

import android.app.Dialog;
import androidx.fragment.app.DialogFragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

/**
 * Created by vapillon on 11/05/15.
 */
public class UiChoiceDialog extends DialogFragment implements View.OnClickListener {

    static final public String UI_CHOICE_LEANBACK_KEY = "uimode_leanback";
    static final public String UI_CHOICE_LEANBACK_TABLET_VALUE = "tablet";
    static final public String UI_CHOICE_LEANBACK_TV_VALUE = "tv";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE); // Thank you stack overflow!

        View v = inflater.inflate(R.layout.ui_choice_dialog, container);

        v.findViewById(R.id.choice_tv).setOnClickListener(this);
        v.findViewById(R.id.choice_tablet).setOnClickListener(this);

        return v;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        d.setTitle(null);
        return d;
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.choice_tv) {
            PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .edit()
                    .putString(UI_CHOICE_LEANBACK_KEY, UI_CHOICE_LEANBACK_TV_VALUE) // string definitions in preference_video.xml and in @array/ui_mode_leanback_entryvalues
                    .apply();

        }
        else if (view.getId() == R.id.choice_tablet) {
            PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .edit()
                    .putString(UI_CHOICE_LEANBACK_KEY, UI_CHOICE_LEANBACK_TABLET_VALUE) // string definitions in preference_video.xml and in @array/ui_mode_leanback_entryvalues
                    .apply();
        }
        dismiss();

        // Quit the current activity and relaunch the entry acivity
        getActivity().finish();

        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setClass(getActivity(), EntryActivity.class);
        startActivity(i);
    }

    /**
     * Utility method to know if the user is using leanback UI or not.
     * Can be if running on a pure leanback device OR on a device using the leanback UI (Android4.4 tv box for example).
     * A bit weird to be a static method in this dialog class, but I did not want to create a new class just for that (for now?)
     * CAUTION: this method does not return false if the user opened the Tablet activity from the "legacy UI" button in the main leanback fragment
     * @param context
     * @return true if the application is in leanback mode
     */
    public static boolean applicationIsInLeanbackMode(Context context) {
        //Save preferences object because we use it more than once.
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        boolean hasLeanbackFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        // Initialize preferences on first run for Android TV
        if (hasLeanbackFeature && !preferences.contains("always_leanback_on_tv_key")) {
            android.util.Log.d("UiChoiceDialog", "First run on Android TV - initializing always_leanback_on_tv_key=true");
            preferences.edit()
                .putBoolean("always_leanback_on_tv_key", true)
                .apply();
        }

        // On Android TV: check always_leanback_on_tv_key preference (default true)
        // On Phone/Tablet: check uimode_leanback preference to allow manual TV interface selection
        if (hasLeanbackFeature) {
            boolean alwaysLeanbackOnTv = preferences.getBoolean("always_leanback_on_tv_key", true);
            android.util.Log.d("UiChoiceDialog", "Android TV: always_leanback_on_tv_key=" + alwaysLeanbackOnTv);
            return alwaysLeanbackOnTv;
        } else {
            // On phones/tablets, check if user manually selected TV interface
            String uiMode = preferences.getString(UI_CHOICE_LEANBACK_KEY, UI_CHOICE_LEANBACK_TABLET_VALUE);
            boolean isLeanbackMode = UI_CHOICE_LEANBACK_TV_VALUE.equals(uiMode);
            android.util.Log.d("UiChoiceDialog", "Phone/Tablet: uimode_leanback=" + uiMode + " (returning " + isLeanbackMode + ")");
            return isLeanbackMode;
        }
    }

    /**
     * Update uimode and uimode_leanback preferences to reflect the current UI mode
     * Should be called after launching MainActivity or MainActivityLeanback
     */
    public static void updateUiModePreferences(Context context, boolean isLeanback) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String modeValue = isLeanback ? UI_CHOICE_LEANBACK_TV_VALUE : UI_CHOICE_LEANBACK_TABLET_VALUE;
        android.util.Log.d("UiChoiceDialog", "Updating UI mode preferences to: " + modeValue);
        preferences.edit()
            .putString(UI_CHOICE_LEANBACK_KEY, modeValue)
            .putString("uimode", isLeanback ? "2" : "1")
            .apply();
    }

    /**
     * Update uimode preferences using an existing editor to batch with other operations
     * Avoids multiple apply() calls when batching SharedPreferences updates
     */
    public static void updateUiModePreferencesInEditor(Context context, SharedPreferences.Editor editor) {
        boolean isLeanback = applicationIsInLeanbackMode(context);
        String modeValue = isLeanback ? UI_CHOICE_LEANBACK_TV_VALUE : UI_CHOICE_LEANBACK_TABLET_VALUE;
        android.util.Log.d("UiChoiceDialog", "Updating UI mode preferences in editor to: " + modeValue);
        editor.putString(UI_CHOICE_LEANBACK_KEY, modeValue);
        editor.putString("uimode", isLeanback ? "2" : "1");
    }
}
