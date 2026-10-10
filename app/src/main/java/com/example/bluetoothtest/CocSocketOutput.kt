package com.example.bluetoothtest

import android.bluetooth.BluetoothSocket
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * LE CoC media output that never hands the stack an SDU larger than either side's packet limit.
 * The ASUS (Qualcomm msmnile, Android 11) stack corrupted any single transmitted CoC SDU beyond
 * ~11.6 KB: its peers allowed 65,535-byte SDUs, so a 16 KB write went out as one SDU and the
 * receiver's integrity check failed at byte 11,627 (also at 12,000-byte writes); 8,087- and
 * 8,088-byte writes verified clean, as did every other phone/direction. In the app this surfaced as
 * "Secure link decrypt failed" on 30-53 KB keyframe records. RFCOMM/GATT are unchanged.
 */
internal fun BluetoothSocket.mediaOutputStream(): OutputStream {
    if (connectionType != BluetoothSocket.TYPE_L2CAP) return outputStream
    // Central links whose control connection is open are measured for the adaptive PHY (AdaptivePhy).
    val socketOutput = AdaptivePhyRegistry.forAddress(runCatching { remoteDevice?.address }.getOrNull())
        ?.let { AdaptivePhyOutputStream(outputStream, it) }
        ?: outputStream
    val maxTransmit = runCatching { maxTransmitPacketSize }.getOrDefault(0)
    val maxReceive = runCatching { maxReceivePacketSize }.getOrDefault(0)
    val coalesce = CocSduCoalescing.effective()
    val chunk = cocWriteChunkBytes(maxTransmit, maxReceive, DebugTunables.cocSinglePduSdus, coalesce)
    // Requested vs effective SDU policy of this socket (the A/B scorer reads it).
    android.util.Log.i(
        "BluetoothVideo",
        CocSduCoalescing.line(DebugTunables.cocCoalesceSdus, coalesce, DebugTunables.cocSinglePduSdus, chunk, maxTransmit, maxReceive),
    )
    chunk ?: return socketOutput
    return ChunkedOutputStream(socketOutput, chunk)
}

/**
 * Largest SDU this phone writes. Each write on the SEQPACKET CoC socket is one SDU (AOSP
 * btif_sock_l2cap reads one message per SDU). With [singlePduSdus], an SDU is at most
 * [COC_SINGLE_PDU_SDU_BYTES], so it travels as one K-frame of at most 251 octets (2-byte SDU length +
 * 4-byte basic header), one full LE data PDU. Before, the A15s offered MPS 251, so every K-frame was
 * 255 octets and went out as a 251-byte ACL packet plus a 4-byte one (HCI snoop
 * slowlink-asussnoop2-b9029e48: 764 x 251 B and 760 x 4 B packets): on a link that moves one PDU per
 * event, half the events carried 4 bytes. The byte stream the peer reads is unchanged.
 */
internal fun cocWriteChunkBytes(
    maxTransmitBytes: Int,
    maxReceiveBytes: Int,
    singlePduSdus: Boolean = false,
    coalesceSdus: Boolean = false,
): Int? {
    val limit = listOf(maxTransmitBytes, maxReceiveBytes).filter { it > 0 }.minOrNull()
    return when {
        // Coalescing (API <= 30 experiment) replaces single-PDU SDUs: fewer, larger SDUs per record.
        coalesceSdus -> minOf(limit ?: COC_COALESCED_SDU_BYTES, COC_COALESCED_SDU_BYTES)
        singlePduSdus -> minOf(limit ?: COC_SINGLE_PDU_SDU_BYTES, COC_SINGLE_PDU_SDU_BYTES)
        else -> limit
    }
}

/**
 * Coalesced SDU size for the Android <= 11 experiment: below the ~11.6 KB at which the ASUS (Android 11)
 * corrupted a single SDU; 8,087/8,088-byte SDUs verified clean (see [mediaOutputStream]). A 50 KB
 * keyframe record then needs 7 SDUs instead of 205.
 */
internal const val COC_COALESCED_SDU_BYTES = 8_000

/**
 * Experimental `test_coc_coalesce_sdus` (default off), scoped by the registry proposal
 * `android-11-coc-small-sdu-coalescing`. Android 11 btif_sock_l2cap reads one SEQPACKET message per
 * SDU into its own buffer and L2CAP counts congestion in queued SDUs (xmit_hold_q > buff_quota), while
 * a write that meets congestion is freed (bta_jv_act.cc:1182, l2c_main.cc:997-1007; 46bd51ed fixed
 * the data loss in Android 13). Fewer, larger SDUs keep the SDU queue short for the same bytes. The app
 * cannot see the stack's queue: no app-side SDU counter, pacing or drop is added; nothing is dropped.
 */
internal object CocSduCoalescing {
    const val ENTRY_ID = "android-11-coc-small-sdu-coalescing"

    /** True when the switch is on and the proposal's matcher holds for this phone. */
    fun effective(
        requested: Boolean = DebugTunables.cocCoalesceSdus,
        traits: DeviceTraits? = runCatching { DeviceTraits.current() }.getOrNull(),
        disabled: Set<String> = runCatching { DeviceFamilyPolicy.switchedOff }.getOrDefault(emptySet()),
    ): Boolean {
        if (!requested || traits == null || "all" in disabled || ENTRY_ID in disabled) return false
        val entry = DeviceFamilyPolicy.entries.firstOrNull { it.id == ENTRY_ID } ?: return false
        return entry.matcher.matches(traits)
    }

    fun line(requested: Boolean, effective: Boolean, singlePdu: Boolean, chunk: Int?, maxTransmit: Int, maxReceive: Int): String =
        "BENCHMARK coc_sdu: requested=${if (requested) "coalesce" else if (singlePdu) "single_pdu" else "socket_limit"} | " +
            "effective=${if (effective) "coalesce" else if (singlePdu) "single_pdu" else "socket_limit"} | " +
            "sduBytes=${chunk ?: "unchunked"} | maxTx=$maxTransmit | maxRx=$maxReceive" +
            if (requested && !effective) " | reason=stack_not_matched" else ""
}

/** 251-octet LE data PDU - 4-byte L2CAP basic header - 2-byte SDU length field (Core Vol 3 Part A 3.4). */
internal const val COC_SINGLE_PDU_SDU_BYTES = 245

internal class ChunkedOutputStream(output: OutputStream, private val chunkBytes: Int) : FilterOutputStream(output) {
    init {
        require(chunkBytes > 0)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val count = minOf(remaining, chunkBytes)
            out.write(b, offset, count)
            offset += count
            remaining -= count
        }
    }
}
