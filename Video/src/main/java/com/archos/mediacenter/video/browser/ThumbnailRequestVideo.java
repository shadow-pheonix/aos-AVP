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

package com.archos.mediacenter.video.browser;

import android.net.Uri;

import java.util.ArrayList;

import com.archos.mediacenter.utils.ThumbnailRequest;

public class ThumbnailRequestVideo extends ThumbnailRequest {

	/**
	 * The cover in the Scraper database
	 */
	private final String mPosterPath;

	/**
	 * There can be a list of posters instead of only one
	 */
	private final ArrayList<String> mPosterPaths;

    /**
     * The file for the Request
     */
    private final Uri mVideoFile2;

    /**
     * Episode-specific picture (TMDb still) - takes priority over poster
     */
    private final String mEpisodePicturePath;

    public ThumbnailRequestVideo(int listPosition, long mediaDbId, String posterPath) {
		super(listPosition, mediaDbId);
		mPosterPath = posterPath;
		mPosterPaths = null;
        mVideoFile2 = null;
        mEpisodePicturePath = null;
	}

    public ThumbnailRequestVideo(int listPosition, long mediaDbId, ArrayList<String> posterPaths) {
        super(listPosition, mediaDbId);
        mPosterPath = null;
        mPosterPaths = posterPaths;
        mVideoFile2 = null;
        mEpisodePicturePath = null;
    }


    public ThumbnailRequestVideo(int listPosition, long mediaDbId, String posterPath, Uri videoFile) {
        super(listPosition, mediaDbId);
        mPosterPath = posterPath;
        mPosterPaths = null;
        mVideoFile2 = videoFile;
        mEpisodePicturePath = null;
    }

    public ThumbnailRequestVideo(int listPosition, long mediaDbId, String posterPath, String episodePicturePath) {
        super(listPosition, mediaDbId);
        mPosterPath = posterPath;
        mPosterPaths = null;
        mVideoFile2 = null;
        mEpisodePicturePath = episodePicturePath;
    }
	public String getPosterPath() {
		return mPosterPath;
	}

    public ArrayList<String> getPostersPaths() {
        return mPosterPaths;
    }

    public Uri getVideoFile() {
        return mVideoFile2;
    }

    public String getEpisodePicturePath() {
        return mEpisodePicturePath;
    }

    @Override
    public Object getKey() {
        return mVideoFile2 != null ? mVideoFile2 : super.getKey();
    }
}
