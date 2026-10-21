package com.example.bluetoothtest

import android.content.pm.PackageInstaller

/** What the update screen shows as its main status. One of these at a time, in this order of precedence. */
internal enum class UpdateScreenState {
    /** A download or verification is running and can be cancelled. */
    CANCELLABLE_BUSY,

    /** This copy cannot use the online check (no release key). */
    NOT_OFFERED,

    /** The user has not said yes to the online check. */
    ONLINE_OFF,

    /** Working, nothing to cancel. */
    BUSY,

    /** A verified file is ready to install. */
    READY,

    /** A newer release is known and can be downloaded. */
    NEWER,

    /** The last check found nothing newer. */
    UP_TO_DATE,

    /** No check has run yet. */
    NOT_CHECKED,
}

/** The update screen's decisions without views, so the flows "check now, no network, newer found, install refused" are tested. */
internal object UpdateScreenModel {
    /** A Cancel tap can arrive after the last read, while Android is inspecting the APK or a UI result is queued. */
    fun finishPreparation(result: PrepareResult, cancelled: Boolean): PrepareResult {
        if (!cancelled) return result
        if (result is PrepareResult.Ready) result.update.file.delete()
        return PrepareResult.Failed(PrepareResult.Kind.CANCELLED, "Cancelled before showing the result")
    }

    fun stateOf(
        busy: Boolean,
        cancellable: Boolean,
        offered: Boolean,
        onlineOn: Boolean,
        verifiedFileReady: Boolean,
        newerKnown: Boolean,
        checkedBefore: Boolean,
    ): UpdateScreenState = when {
        busy && cancellable -> UpdateScreenState.CANCELLABLE_BUSY
        !offered -> UpdateScreenState.NOT_OFFERED
        !onlineOn -> UpdateScreenState.ONLINE_OFF
        busy -> UpdateScreenState.BUSY
        verifiedFileReady -> UpdateScreenState.READY
        newerKnown -> UpdateScreenState.NEWER
        checkedBefore -> UpdateScreenState.UP_TO_DATE
        else -> UpdateScreenState.NOT_CHECKED
    }

    /** The sentence for a download, verification or import that did not produce a file. */
    fun failureTextRes(failed: PrepareResult.Failed): Int = when (failed.kind) {
        PrepareResult.Kind.BUSY -> R.string.update_busy_media
        PrepareResult.Kind.OFFLINE -> R.string.update_fail_offline
        PrepareResult.Kind.SERVER -> R.string.update_fail_server
        PrepareResult.Kind.RATE_LIMITED -> R.string.update_fail_rate
        PrepareResult.Kind.NO_FILE -> R.string.update_fail_no_file
        PrepareResult.Kind.NO_HASH -> R.string.update_fail_no_hash
        PrepareResult.Kind.HASH_MISMATCH -> R.string.update_fail_hash
        PrepareResult.Kind.STORAGE -> R.string.update_fail_storage
        PrepareResult.Kind.CANCELLED -> R.string.update_fail_cancelled
        PrepareResult.Kind.UNREADABLE -> R.string.update_fail_unreadable
        PrepareResult.Kind.CHECKS_OFF -> R.string.update_online_off
        PrepareResult.Kind.REFUSED -> when (failed.refusal) {
            UpdateRefusal.WRONG_PACKAGE -> R.string.update_refused_package
            UpdateRefusal.NOT_NEWER -> R.string.update_refused_not_newer
            UpdateRefusal.SDK_TOO_OLD -> R.string.update_refused_sdk
            UpdateRefusal.WRONG_ABI -> R.string.update_refused_abi
            UpdateRefusal.SIGNER_COUNT -> R.string.update_refused_signers
            UpdateRefusal.SIGNER_NOT_INSTALLED -> R.string.update_refused_signer_installed
            UpdateRefusal.SIGNER_NOT_PINNED -> R.string.update_refused_signer_pinned
            UpdateRefusal.ONLINE_UPDATE_NOT_OFFERED -> R.string.update_refused_online_off
            else -> R.string.update_refused_unreadable
        }
    }

    /**
     * What to tell the user about the installer's last answer, or null for nothing: no answer on record, or the user
     * themselves refused Android's confirmation (that needs no explanation).
     */
    fun installFailureDetail(code: Int, message: String): String? = when (code) {
        0, PackageInstaller.STATUS_FAILURE_ABORTED -> null
        else -> message.ifEmpty { code.toString() }
    }
}
