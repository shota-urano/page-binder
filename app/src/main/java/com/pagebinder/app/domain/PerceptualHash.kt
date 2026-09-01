package com.pagebinder.app.domain

/** Framework-free pHash comparison shared by manual and continuous capture decisions. */
fun isNearDuplicateHash(
    first: String,
    second: String?,
): Boolean {
    if (second == null) return false
    if (first == second) return true
    if (first.length != HASH_HEX_LENGTH || second.length != HASH_HEX_LENGTH) return false
    return runCatching {
        java.lang.Long.bitCount(
            java.lang.Long.parseUnsignedLong(first, HEX_RADIX) xor
                java.lang.Long.parseUnsignedLong(second, HEX_RADIX),
        ) <= NEAR_DUPLICATE_HASH_DISTANCE
    }.getOrDefault(false)
}

private const val HASH_HEX_LENGTH = 16
private const val HEX_RADIX = 16
private const val NEAR_DUPLICATE_HASH_DISTANCE = 5
