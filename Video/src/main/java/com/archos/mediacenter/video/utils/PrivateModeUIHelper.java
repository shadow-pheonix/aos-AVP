// Copyright 2025 Nova Video Player
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

package com.archos.mediacenter.video.utils;

import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.player.PrivateMode;

/**
 * Utility class to manage private mode UI indicators across fragments
 */
public class PrivateModeUIHelper {

    /**
     * Add a private mode indicator icon to the fragment's root view
     * Call this from onViewCreated() of fragments that need the indicator
     * 
     * @param activity The host activity
     * @param rootView The root view of the fragment (must be a ViewGroup)
     * @return The created ImageView, or null if rootView is not a ViewGroup
     */
    public static ImageView addPrivateModeIndicator(Activity activity, View rootView) {
        if (!(rootView instanceof ViewGroup)) {
            return null;
        }
        
        ViewGroup rootViewGroup = (ViewGroup) rootView;
        
        // Check if indicator already exists
        View existingIndicator = rootView.findViewById(R.id.private_mode_indicator);
        if (existingIndicator != null) {
            return (ImageView) existingIndicator;
        }
        
        ImageView privateModeIndicator = new ImageView(activity);
        privateModeIndicator.setId(R.id.private_mode_indicator);
        privateModeIndicator.setImageResource(R.drawable.ic_incognito);
        privateModeIndicator.setAlpha(0.6f);
        privateModeIndicator.setVisibility(PrivateMode.isActive() ? View.VISIBLE : View.GONE);
        privateModeIndicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        
        // Position at bottom-right with margin
        Resources resources = activity.getResources();
        int sizePx = resources.getDimensionPixelSize(R.dimen.private_mode_indicator_size);
        int marginPx = resources.getDimensionPixelSize(R.dimen.private_mode_indicator_margin);
        
        // Use appropriate LayoutParams based on root view type
        ViewGroup.LayoutParams params;
        if (rootViewGroup instanceof FrameLayout) {
            // FrameLayout supports gravity for positioning
            FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(sizePx, sizePx);
            frameParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            frameParams.setMargins(0, 0, marginPx, marginPx);
            params = frameParams;
        } else if (rootViewGroup instanceof android.widget.RelativeLayout) {
            // RelativeLayout uses alignParentBottom/End rules
            android.widget.RelativeLayout.LayoutParams relativeParams = 
                new android.widget.RelativeLayout.LayoutParams(sizePx, sizePx);
            relativeParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            relativeParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
            relativeParams.setMargins(0, 0, marginPx, marginPx);
            params = relativeParams;
        } else {
            // For other layouts, use default params - it will appear at (0,0) position
            // but we can set padding to push it to the right
            params = new ViewGroup.LayoutParams(sizePx, sizePx);
        }
        
        rootViewGroup.addView(privateModeIndicator, params);
        
        return privateModeIndicator;
    }
    
    /**
     * Update the visibility of the private mode indicator
     * Call this whenever the background is updated or private mode is toggled
     * 
     * @param rootView The root view containing the indicator
     */
    public static void updatePrivateModeIndicator(View rootView) {
        if (rootView == null) return;
        
        View privateModeIndicator = rootView.findViewById(R.id.private_mode_indicator);
        if (privateModeIndicator != null) {
            privateModeIndicator.setVisibility(PrivateMode.isActive() ? View.VISIBLE : View.GONE);
        }
    }
}