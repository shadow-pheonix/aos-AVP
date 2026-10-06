// Copyright 2025 Courville Software
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

package com.archos.mediacenter.video.browser.loader;

import android.content.Context;

import com.archos.mediaprovider.video.VideoStore;

public class WatchingUpNextLoader extends VideoLoader {

    public WatchingUpNextLoader(Context context) {
        super(context);
        init();
        // cf. https://github.com/nova-video-player/aos-AVP/issues/134 reduce strain
        // only updates the CursorLoader on data change every 10s since used only in MainFragment as nonScraped box presence
        if (VideoLoader.ALLVIDEO_THROTTLE) setUpdateThrottle(VideoLoader.ALLVIDEO_THROTTLE_DELAY);
    }

    @Override
    public String getSelection() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.getSelection());

        if (builder.length() > 0)
            builder.append(" AND ");

        // display first episodes or movies being watched (bookmark>0 and Archos_traktSeen=0) ordered by Archos_lastTimePlayed with more recent first
        // then next episodes in tvshow or movie in collection based on Archos_traktSeen=1 viewed ones ordered by Archos_lastTimePlayed of the former episode watched that was Archos_traktSeen=1.
        // /!\ note that it assumes that there is no more than 1000 episodes in a season

        // Index-optimized query: Restructured to leverage new performance indexes
        builder.append(
                "(" +
                // Part 1: Next episodes in TV series
                "(s_id IS NOT NULL AND Archos_traktSeen = 0 AND archos_hiddenbyuser = 0 AND (bookmark IS NULL OR bookmark <= 0) AND " +
                "_id IN (SELECT _id FROM (SELECT v._id, ROW_NUMBER() OVER(PARTITION BY v.s_id ORDER BY v.e_season, v.e_episode) as rn FROM video v JOIN ( SELECT s_id, MAX(e_season * 1000 + e_episode) AS max_watched_episode FROM video WHERE Archos_traktSeen = 1 AND archos_hiddenbyuser = 0 GROUP BY s_id ) AS lw ON v.s_id = lw.s_id WHERE v.Archos_traktSeen = 0 AND v.archos_hiddenbyuser = 0 AND (v.e_season * 1000 + v.e_episode) > lw.max_watched_episode ) WHERE rn = 1))" +
                " OR " +
                // Part 2: Next movies in collections
                "(m_coll_id IS NOT NULL AND Archos_traktSeen = 0 AND archos_hiddenbyuser = 0 AND (bookmark IS NULL OR bookmark <= 0) AND " +
                "_id IN (SELECT _id FROM (SELECT v._id, ROW_NUMBER() OVER(PARTITION BY v.m_coll_id ORDER BY v.m_year) as rn FROM video v JOIN ( SELECT m_coll_id, MAX(m_year) AS max_watched_year FROM video WHERE Archos_traktSeen = 1 AND archos_hiddenbyuser = 0 GROUP BY m_coll_id ) AS lw ON v.m_coll_id = lw.m_coll_id WHERE v.Archos_traktSeen = 0 AND v.archos_hiddenbyuser = 0 AND v.m_year > lw.max_watched_year ) WHERE rn = 1))" +
                " OR " +
                // Part 3: Currently watching videos (partially watched, not completed)
                "(bookmark > 0 AND Archos_traktSeen = 0 AND archos_hiddenbyuser = 0 AND bookmark != -2)" +
                ")"
        );

        return builder.toString();
    }

    // display first the currently in progress (not Archos_traktSeen and bookmark>0) with Archos_lastTimePlayed more recent first, and the
    // next to watch movie and episodes ordered by Archos_lastTimePlayed of the former episode watched that was Archos_traktSeen=1.

    @Override
    public String getSortOrder() {
        // Priority 1: Currently watching videos (bookmark > 0), sorted by most recent play time.
        // Priority 2: Next episodes/movies, sorted by the play time of the *last watched* item in the series/collection.
        return "CASE " +
            "WHEN bookmark > 0 AND Archos_traktSeen = 0 THEN 1 " + // Group 1: In progress
            "ELSE 2 " + // Group 2: Up next
        "END, " +
        "CASE " +
            // Sort for Group 1: Currently watching videos by most recent play time
            "WHEN bookmark > 0 AND Archos_traktSeen = 0 THEN Archos_lastTimePlayed " +
            // Sort for Group 2: Up next items by last watched item's play time
            "ELSE COALESCE(" +
                // For series: get the play time of the most recently *watched* episode
                "(SELECT lw.Archos_lastTimePlayed FROM video lw WHERE lw.s_id = video.s_id AND lw.Archos_traktSeen = 1 ORDER BY lw.e_season DESC, lw.e_episode DESC LIMIT 1), " +
                // For collections: get the play time of the most recently *watched* movie
                "(SELECT lw.Archos_lastTimePlayed FROM video lw WHERE lw.m_coll_id = video.m_coll_id AND lw.Archos_traktSeen = 1 ORDER BY lw.m_year DESC LIMIT 1), " +
                "0" + // Fallback value if no watched item is found
            ") " +
        "END DESC, " +
        // Add secondary sort for stability and logical ordering within the same play time
        "s_name, e_season, e_episode, m_name, m_year" +
        " LIMIT 50";
    }
}