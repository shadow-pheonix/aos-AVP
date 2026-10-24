/* SPDX-License-Identifier: Apache-2.0 */
#ifndef NOVA_DOVI_COMPAT_H
#define NOVA_DOVI_COMPAT_H
#include <stddef.h>
#include <stdint.h>
/* Only call for HEVC Profile 7 with compatibility mode enabled.
 * length_size: 1..4 for hvcC length prefixes; 0 for Annex B.
 * On success caller owns *output (free); input remains unchanged on failure.
 * Returns 0 on success, -1 for invalid input/conversion failure, -2 for OOM.
 */
int nova_dovi_convert(const uint8_t *input, size_t size, unsigned length_size,
                      uint8_t **output, size_t *output_size);
#endif
