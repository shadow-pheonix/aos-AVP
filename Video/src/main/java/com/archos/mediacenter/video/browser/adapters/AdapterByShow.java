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


package com.archos.mediacenter.video.browser.adapters;


import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import com.archos.mediacenter.video.browser.ThumbnailRequestVideo;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Video;

public class AdapterByShow extends PresenterAdapterByCursor implements AdapterByVideoObjectsInterface {

    private final VideoCursorMapper mVideoCursorMapper;
    public static final int ITEM_VIEW_TYPE_SHOW = 0;
    private final static boolean DBG = false;
    private final static String TAG = "AdapterByShow";

    public AdapterByShow(Context context, Cursor c) {
        super(context, c);

        mVideoCursorMapper = new VideoCursorMapper();
        mVideoCursorMapper.publicBindColumns(c);
    }

    private int getItemType(int position) {
        return  ITEM_VIEW_TYPE_SHOW;
    }

    @Override
    public Object getItem(int position){
        if (DBG) Log.d("showdebug", "get " + position);
        Cursor c = getCursor();
        if (c == null || !c.moveToPosition(position)) {
            return null;
        }
        return mVideoCursorMapper.publicBind(c);
    }

    public boolean isEnabled(int position) {
        return true;
    }

    @Override
    public Video getVideoItem(int position) {
            return (Video) getItem(position);
    }

    @Override
    public ThumbnailRequestVideo getThumbnailRequest(int position) {
        Cursor c = getCursor();
        if (c == null || !c.moveToPosition(position)) {
            return null;
        }
        
        Object item = mVideoCursorMapper.publicBind(c);
        if (DBG) Log.d(TAG, "getThumbnailRequest: position=" + position + ", item class=" + item.getClass().getSimpleName());
        if (item instanceof Episode) {
            Episode episode = (Episode) item;
            if (DBG) Log.d(TAG, "getThumbnailRequest: episode.getPictureUri()=" + episode.getPictureUri());
            if (episode.getPictureUri() != null) {
                // Use episode picture (TMDb still) when available
                String episodePicturePath = episode.getPictureUri().getPath();
                String posterPath = getCover();
                if (DBG) Log.d(TAG, "getThumbnailRequest: episodePicturePath=" + episodePicturePath + ", posterPath=" + posterPath);
                return new ThumbnailRequestVideo(position, getItemId(position), posterPath, episodePicturePath);
            }
        }
        // Fall back to default behavior (season poster)
        if (DBG) Log.d(TAG, "getThumbnailRequest: falling back to super.getThumbnailRequest");
        return super.getThumbnailRequest(position);
    }

}
