package com.example.bluetoothtest

/**
 * Semantic version of a release tag or `versionName` (`0.1.0-beta`, `v0.2.0`, `1.0.0-beta.2`),
 * ordered by semver precedence: a pre-release sorts below its release, numeric pre-release parts
 * compare as numbers and sort below text parts, a longer pre-release wins when the rest is equal,
 * build metadata (`+...`) is ignored. The updater only uses it to decide whether to *offer* a
 * download; the installed `versionCode` of the downloaded APK is the gate that counts
 * ([UpdateVerifier]).
 */
internal class ReleaseVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
    private val preRelease: List<String>,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return compareValues(other.preRelease.size.coerceAtMost(1), preRelease.size.coerceAtMost(1))
        }
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[index]
            val b = other.preRelease[index]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val order = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is ReleaseVersion && compareTo(other) == 0

    override fun hashCode(): Int = listOf(major, minor, patch, preRelease).hashCode()

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex(
            """^v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-([0-9A-Za-z-]{1,20}(?:\.[0-9A-Za-z-]{1,20}){0,3}))?(?:\+[0-9A-Za-z.-]{1,40})?$""",
        )

        /** Null when [text] is not a semantic version (the updater then offers nothing). */
        fun parse(text: String): ReleaseVersion? {
            // Bounded: the text is shown to the user, so a release tag cannot carry a message (at most 4 short pre-release parts).
            if (text.length > 80) return null
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return ReleaseVersion(
                major.toInt(),
                minor.toInt(),
                patch.toInt(),
                if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }
    }
}

/**
 * The release number of a build: its base `versionCode` (README "Distribution": a split release APK has
 * `versionCode = base * 10 + ABI digit`, universal 0, armeabi-v7a 1, arm64-v8a 2). It is what the link
 * hello's build tag carries (PROTOCOL "Build tag in the link hello"), without the ABI digit.
 */
internal object ReleaseNumber {
    const val MAX = 0x7FFF
    const val ABI_UNIVERSAL = 0
    const val ABI_ARMEABI_V7A = 1
    const val ABI_ARM64_V8A = 2

    /** Null for a build that is not a split release (debug and non-split builds have a code below 10). */
    fun fromVersionCode(versionCode: Long): Int? =
        (versionCode / 10).takeIf { versionCode >= 10 && it in 1..MAX.toLong() }?.toInt()

    fun abiDigit(versionCode: Long): Int = (versionCode % 10).toInt()

    fun abiName(digit: Int): String? = when (digit) {
        ABI_UNIVERSAL -> "universal"
        ABI_ARMEABI_V7A -> "armeabi-v7a"
        ABI_ARM64_V8A -> "arm64-v8a"
        else -> null
    }
}
