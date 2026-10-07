#!/usr/bin/env python3
"""Run the production tempo checkpoint queue/poll with deterministic clocks.

The host harness isolates these functions from Android/JNI. It reproduces the
startup ledger phase observed in device diagnostics, and verifies real speed
changes and reset checkpoints still commit in their presentation order.
"""
from pathlib import Path
import os
import subprocess
import tempfile


def function(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


source = (Path(__file__).resolve().parents[1] / "Source/stream.c").read_text()
production = "\n".join(function(source, signature) for signature in (
    "int stream_atempo_commit_queue(",
    "static void _stream_atempo_commit_poll(",
))
stub = r'''
#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include <stdarg.h>
typedef uint64_t UINT64;
#define STREAM_ATEMPO_COMMIT_MAX 8
#define STREAM_ATEMPO_COMMIT_BOUNDARY_DEFER UINT64_MAX
#define STREAM_NO_PTS_VALUE -1
#define MAX(a,b) ((a) > (b) ? (a) : (b))
#define DBG if (0)
static void serprintf(const char *fmt, ...) { (void)fmt; }
static void playback_diagnostic(const char *fmt, ...) { (void)fmt; }
typedef struct { float speed, prev_speed; UINT64 boundary; int64_t wall_ms; } STREAM_ATEMPO_COMMIT;
typedef struct { int valid; } VIDEO;
typedef struct STREAM STREAM;
typedef struct { void (*set_playback_speed)(void *, int, int); } DECODER;
struct STREAM {
    int atempo_commit_count, atempo_commit_head, video_speed_num, video_speed_den;
    int aborted, paused, paused_internal, audio_time, video_time;
    int64_t atempo_ledger_dense_until_ms;
    UINT64 atempo_ledger_output_frames;
    STREAM_ATEMPO_COMMIT atempo_commit_q[STREAM_ATEMPO_COMMIT_MAX];
    pthread_mutex_t video_control_mutex;
    VIDEO *video;
    DECODER *video_dec;
};
static int64_t wall_ms = 1000;
static int64_t atime64(void) { return wall_ms; }
static int have_ph = 1, ledger_state = 0, heard_ts = 889713, media_rst = 888958;
static UINT64 presented = 4096;
static double rst_anchor, ts_anchor, map_speed = 1;
static int map_calls, sink_calls;
static int stream_atempo_presentation(STREAM *s, UINT64 *ph, int *rate, int *ts, int *rst, int *state) {
    (void)s; *ph = presented; *rate = 48000; *ts = heard_ts; *rst = media_rst; *state = ledger_state;
    return have_ph;
}
static int stream_get_heard_audio_ts(STREAM *s, int fallback) { (void)s; (void)fallback; return heard_ts; }
#define TS_TO_RST_TIME(ts,type) ((type)(rst_anchor + ((ts) - ts_anchor) * map_speed))
static void timeline_map_apply(double rst, double ts, float speed) {
    rst_anchor = rst; ts_anchor = ts; map_speed = speed; map_calls++;
}
static void _stream_anchor_video_sink_to_audio_clock(STREAM *s, int ts) { (void)s; (void)ts; sink_calls++; }
static STREAM make_stream(void) {
    STREAM s = {0}; s.video_speed_num = s.video_speed_den = 100;
    s.atempo_ledger_output_frames = 8192;
    s.video_control_mutex = (pthread_mutex_t)PTHREAD_MUTEX_INITIALIZER;
    rst_anchor = ts_anchor = 0; map_speed = 1; map_calls = sink_calls = 0;
    wall_ms = 1000; have_ph = 1; ledger_state = 0; presented = 4096;
    return s;
}
'''
tests = r'''
int main(void) {
    STREAM s = make_stream();
    assert(stream_atempo_commit_queue(&s, 1.0f, 0) == 0);
    _stream_atempo_commit_poll(&s, 0);
    double scheduled_pts = ts_anchor + (888958 - rst_anchor) / map_speed;
    printf("startup decoded=888958 scheduled=%.0f added_video_hold=%.0fms\n", scheduled_pts, scheduled_pts - 888958);
    fflush(stdout);
    assert(scheduled_pts == 888958);
    assert(s.atempo_commit_count == 0 && map_calls == 0 && sink_calls == 0);

    // A real change still waits for accepted PCM to cross its boundary.
    s = make_stream();
    assert(stream_atempo_commit_queue(&s, 1.5f, 5000) == 0);
    _stream_atempo_commit_poll(&s, 0);
    assert(s.atempo_commit_count == 1 && map_calls == 0);
    presented = 6000;
    _stream_atempo_commit_poll(&s, 0);
    assert(s.atempo_commit_count == 0 && map_calls == 1);
    assert(s.video_speed_num == 150 && map_speed == 1.5);

    // Return to 1x follows the pending 1.5x checkpoint, never the current 1x.
    s = make_stream();
    assert(stream_atempo_commit_queue(&s, 1.5f, 5000) == 0);
    assert(stream_atempo_commit_queue(&s, 1.0f, 7000) == 0);
    assert(s.atempo_commit_count == 2);
    presented = 6000; _stream_atempo_commit_poll(&s, 0);
    assert(s.video_speed_num == 150 && s.atempo_commit_count == 1);
    presented = 7500; _stream_atempo_commit_poll(&s, 0);
    assert(s.video_speed_num == 100 && s.atempo_commit_count == 0);

    // A pending reset owns a new ledger epoch even when the speed is equal.
    s = make_stream();
    assert(stream_atempo_commit_queue(&s, 1.0f, STREAM_ATEMPO_COMMIT_BOUNDARY_DEFER) == 0);
    assert(s.atempo_commit_count == 1);
    assert(stream_atempo_commit_queue(&s, 1.0f, 0) == 0);
    assert(s.atempo_commit_count == 1);
    _stream_atempo_commit_poll(&s, 0);
    assert(map_calls == 1);

    // Pause cannot apply a real speed checkpoint; resume can.
    s = make_stream(); s.paused = 1;
    assert(stream_atempo_commit_queue(&s, 1.5f, 0) == 0);
    _stream_atempo_commit_poll(&s, 0); assert(map_calls == 0);
    s.paused = 0; _stream_atempo_commit_poll(&s, 0); assert(map_calls == 1);
    puts("tempo startup and ordered speed/reset checkpoint regression checks passed");
    return 0;
}
'''
with tempfile.TemporaryDirectory(prefix="avos-tempo-startup-") as directory:
    root = Path(directory)
    c_file = root / "test.c"
    c_file.write_text(stub + production + tests)
    binary = root / "test"
    subprocess.run([os.environ.get("CC", "cc"), "-std=gnu11", "-Wall", "-Wextra",
                    "-fsanitize=undefined", str(c_file), "-pthread", "-lm", "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
