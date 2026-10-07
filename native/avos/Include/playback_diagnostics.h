/*
 * Copyright 2017 Archos SA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef NOVA_PLAYBACK_DIAGNOSTICS_H
#define NOVA_PLAYBACK_DIAGNOSTICS_H

#include "global.h"
#include "log.h"
#include <stdarg.h>
#ifdef CONFIG_ANDROID
#include <android/log.h>
#endif

// Callers sample at most once per second. Keep diagnostic evidence available
// in release builds without enabling all AVOS debug output or querying clocks.
static inline void playback_diagnostic(const char *format, ...)
{
    va_list args;
    va_start(args, format);
#ifdef CONFIG_ANDROID
    __android_log_vprint(ANDROID_LOG_INFO, "Nova-playback", format, args);
#else
    vserprintf(format, args);
#endif
    va_end(args);
}
#endif
