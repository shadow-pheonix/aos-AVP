/* SPDX-License-Identifier: MIT
 * Minimal declarations for the pinned dolby_vision C API.
 * ABI source: quietvoid/dovi_tool 26ed61ff36f946313889a09c0d2da202e9ceb97a,
 * dolby_vision/src/capi.rs and c_structs/buffers.rs.
 */
#ifndef NOVA_DOVI_FFI_H
#define NOVA_DOVI_FFI_H
#include <stddef.h>
#include <stdint.h>
typedef struct NovaDoviRpu NovaDoviRpu;
typedef struct NovaDoviData {
    const uint8_t *data;
    size_t len;
} NovaDoviData;
NovaDoviRpu *dovi_parse_unspec62_nalu(const uint8_t *, size_t);
const char *dovi_rpu_get_error(const NovaDoviRpu *);
int32_t dovi_convert_rpu_with_mode(NovaDoviRpu *, uint8_t);
const NovaDoviData *dovi_write_unspec62_nalu(NovaDoviRpu *);
void dovi_data_free(const NovaDoviData *);
void dovi_rpu_free(NovaDoviRpu *);
#endif
