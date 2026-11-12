package com.example.target.keypairs

class TernaryEditBeforeMergeV1 {
    fun check(
        flag: Boolean,
        x: Int,
        y: Int,
    ): String = if ((if (flag) x else y) > 0) "pos" else "non-pos"
}

// The edited true arm runs before the operand stack is next empty, so the outer condition's
// window starts at the false arm and leaves this edit out.
class TernaryEditBeforeMergeV2 {
    fun check(
        flag: Boolean,
        x: Int,
        y: Int,
    ): String = if ((if (flag) x + 1 else y) > 0) "pos" else "non-pos"
}

class TernaryEditBeforeMergeV3 {
    fun check(
        flag: Boolean,
        x: Int,
        y: Int,
    ): String = if ((if (flag) x else y + 1) > 0) "pos" else "non-pos"
}
