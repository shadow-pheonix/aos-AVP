/* SPDX-License-Identifier: Apache-2.0
 * Profile 7 -> 8.1 playback conversion using libdovi mode 2, as in Kodi's
 * compatibility mode. No Kodi player code is included.
 */
#include "dovi_compat.h"
#include "nova_dovi_ffi.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>

typedef struct Output {
    uint8_t *data;
    size_t size;
} Output;

static int append(Output *out, const uint8_t *data, size_t size)
{
    if (size > INT_MAX || out->size > (size_t)INT_MAX - size)
        return -1;
    uint8_t *p = realloc(out->data, out->size + size);
    if (!p && size)
        return -2;
    out->data = p;
    if (size)
        memcpy(p + out->size, data, size);
    out->size += size;
    return 0;
}

static size_t start_code(const uint8_t *p, size_t n)
{
    if (n >= 4 && !p[0] && !p[1] && !p[2] && p[3] == 1)
        return 4;
    if (n >= 3 && !p[0] && !p[1] && p[2] == 1)
        return 3;
    return 0;
}

static int write_nal(Output *out, const uint8_t *nal, size_t size,
                     const uint8_t *prefix, unsigned prefix_size, int annexb)
{
    int ret = -1;
    const NovaDoviData *converted = NULL;
    NovaDoviRpu *rpu = NULL;
    uint8_t length[4];
    if (size < 2 || (nal[0] & 0x80) || !(nal[1] & 7))
        return -1;
    unsigned type = (nal[0] >> 1) & 63;
    unsigned layer = ((nal[0] & 1) << 5) | (nal[1] >> 3);
    /* Discard encapsulated EL and EL NALs in dual-layer access units. */
    if (type == 63 || layer)
        return 0;
    if (type == 62) {
        rpu = dovi_parse_unspec62_nalu(nal, size);
        if (!rpu || dovi_rpu_get_error(rpu) ||
            dovi_convert_rpu_with_mode(rpu, 2) < 0)
            goto done;
        converted = dovi_write_unspec62_nalu(rpu);
        if (!converted || !converted->data || converted->len < 2)
            goto done;
        nal = converted->data;
        size = converted->len;
    }
    if (!annexb) {
        /* Check representability before writing a shorter length prefix. */
        if (size > UINT32_MAX || (prefix_size < 4 &&
            size >= ((size_t)1 << (8 * prefix_size))))
            goto done;
        for (unsigned i = 0; i < prefix_size; i++)
            length[prefix_size - i - 1] = (uint8_t)(size >> (8 * i));
        prefix = length;
    }
    ret = append(out, prefix, prefix_size);
    if (!ret)
        ret = append(out, nal, size);
done:
    if (converted)
        dovi_data_free(converted);
    if (rpu)
        dovi_rpu_free(rpu);
    return ret;
}

int nova_dovi_convert(const uint8_t *input, size_t size, unsigned length_size,
                      uint8_t **output, size_t *output_size)
{
    Output out = {0};
    size_t offset = 0;
    int ret = -1;
    if (!output || !output_size)
        return -1;
    *output = NULL;
    *output_size = 0;
    if (!input || !size || size > INT_MAX || length_size > 4)
        return -1;
    if (!length_size) {
        /* Annex B allows leading_zero_8bits before the first start code. */
        while (offset < size && !start_code(input + offset, size - offset)) {
            if (input[offset++])
                goto fail;
        }
        if (offset == size)
            goto fail;
        ret = append(&out, input, offset);
        if (ret)
            goto fail;
    }
    while (offset < size) {
        const uint8_t *prefix = input + offset;
        size_t nal_size = 0;
        unsigned prefix_size = length_size;
        if (length_size) {
            if (size - offset < length_size) { ret = -1; goto fail; }
            for (unsigned i = 0; i < length_size; i++)
                nal_size = (nal_size << 8) | input[offset + i];
        } else {
            prefix_size = (unsigned)start_code(prefix, size - offset);
            if (!prefix_size) { ret = -1; goto fail; }
        }
        offset += prefix_size;
        if (!length_size) {
            size_t end = offset;
            while (end < size && !start_code(input + end, size - end))
                end++;
            nal_size = end - offset;
        }
        if (nal_size > size - offset) { ret = -1; goto fail; }
        ret = write_nal(&out, input + offset, nal_size, prefix, prefix_size,
                        !length_size);
        if (ret)
            goto fail;
        offset += nal_size;
    }
    *output = out.data;
    *output_size = out.size;
    return 0;
fail:
    free(out.data);
    return ret;
}
