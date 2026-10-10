package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.view.WindowManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DebugBenchmarkCoordinator(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var rawBenchmark: BluetoothRawBenchmark? = null
    private var gattBenchmark: BleGattBenchmark? = null
    private var relayDownstream: BleGattBenchmark? = null
    private var relayUpstreamScan: Closeable? = null
    private var discoveryScan: Closeable? = null

    fun runOffline(intent: Intent) {
        check(!closed.get())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val caseName = intent.getStringExtra("benchmark_case") ?: "encoder_benchmark"
        val inputName = intent.getStringExtra("benchmark_input") ?: "reference.yuv"
        val spec = EncoderFileBenchmarkSpec(
            caseName = caseName,
            codec = intent.getStringExtra("benchmark_codec") ?: "av1",
            codecName = intent.getStringExtra("benchmark_codec_name"),
            inputFile = java.io.File(activity.filesDir, "benchmark-input/$inputName"),
            outputDirectory = java.io.File(activity.getExternalFilesDir(null), "offline-benchmarks"),
            width = intent.getIntExtra("benchmark_width", 1280),
            height = intent.getIntExtra("benchmark_height", 720),
            fps = intent.getIntExtra("benchmark_fps", 30),
            frames = intent.getIntExtra("offline_frames", 90),
            bitrateKbps = intent.getIntExtra("benchmark_bitrate_kbps", 400),
            complexity = intent.getIntExtra("benchmark_complexity", 0),
            rateMode = intent.getStringExtra("benchmark_rate_mode") ?: "cbr",
            keyFrameIntervalSeconds = intent.getIntExtra("benchmark_keyframe_seconds", 4),
            nativeTuning = NativeAomTuning(
                qualityMode = intent.getBooleanExtra("benchmark_native_quality_mode", false),
                lagFrames = intent.getIntExtra("benchmark_native_lag_frames", 0),
                aqMode = intent.getIntExtra("benchmark_native_aq", 3),
                tuneSsim = intent.getBooleanExtra("benchmark_native_ssim", false),
                noiseSensitivity = intent.getIntExtra("benchmark_native_denoise", 0),
                maxQuantizer = intent.getIntExtra("benchmark_native_max_q", 63),
                rateBufferMs = intent.getIntExtra("benchmark_native_buffer_ms", 1_000),
                enableCdef = intent.getBooleanExtra("benchmark_native_cdef", true),
                maxReferenceFrames = intent.getIntExtra("benchmark_native_refs", 3),
                realtimeToolProfile = intent.getBooleanExtra(
                    "benchmark_native_realtime_tools",
                    false,
                ),
            ),
            nativeThreads = intent.getIntExtra("benchmark_native_threads", 0)
                .takeIf { it > 0 },
        )
        onStatus("Running offline encoder benchmark $caseName…")
        thread(name = "OfflineEncoderBenchmark") {
            runCatching { EncoderFileBenchmark.run(activity, spec) }
                .onSuccess { resultFile ->
                    onStatus("Benchmark finished: ${resultFile.name}\n${resultFile.readText()}")
                }
                .onFailure { error ->
                    onStatus("Benchmark failed: ${error.message ?: error.javaClass.simpleName}")
                }
            finishRun()
        }
    }

    @SuppressLint("MissingPermission")
    fun runTransport(
        intent: Intent,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
    ) {
        check(!closed.get())
        val experiment = intent.getStringExtra("test_experiment")?.lowercase()
            ?: error("Missing test_experiment")
        val durationSeconds = intent.getIntExtra("test_duration_seconds", 15).coerceIn(1, 120)
        val phyPreference = BlePhyPreference.TWO_M
        val connectionPriority = BleConnectionPriority.fromDebugValue(intent.getStringExtra("test_ble_priority"))
        when (experiment) {
            "raw_rx", "raw_tx" -> runRaw(
                intent,
                adapter,
                transport,
                experiment,
                durationSeconds,
                phyPreference,
                connectionPriority,
            )
            "gatt_rx", "gatt_tx", "gatt_notify_tx", "gatt_notify_rx" -> runGatt(
                adapter,
                experiment,
                durationSeconds,
                phyPreference,
                connectionPriority,
                intent.getIntExtra("test_fence_packets", 2048).coerceIn(1, 2048),
                intent.tag("test_advertise_tag"),
                intent.tag("test_target_tag"),
                intent.getIntExtra("test_rate_kbps", 0).coerceAtLeast(0),
            )
            "gatt_relay_mixed", "gatt_relay_central" -> runGattRelay(
                adapter,
                central = experiment == "gatt_relay_central",
                durationSeconds,
                phyPreference,
                BleConnectionPriority.fromDebugValue(
                    intent.getStringExtra("test_upstream_priority") ?: intent.getStringExtra("test_ble_priority"),
                ),
                BleConnectionPriority.fromDebugValue(
                    intent.getStringExtra("test_downstream_priority") ?: intent.getStringExtra("test_ble_priority"),
                ),
                upstreamTag = intent.tag("test_upstream_tag") ?: error("Missing test_upstream_tag"),
                downstreamTag = intent.tag("test_downstream_tag") ?: error("Missing test_downstream_tag"),
                upstreamDelayMs = intent.getIntExtra("test_upstream_delay_ms", 3_000).toLong(),
                downstreamRateKbps = intent.getIntExtra("test_downstream_rate_kbps", 0).coerceAtLeast(0),
            )
            else -> {
                onStatus("Unknown transport experiment: $experiment")
                finishRun()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRaw(
        intent: Intent,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
        experiment: String,
        durationSeconds: Int,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
    ) {
        val config = RawBenchmarkConfig(
            payloadBytes = intent.getIntExtra("test_payload_bytes", 0)
                .takeIf { it > 0 }
                ?.coerceIn(1, RawBenchmarkConfig.MAX_PAYLOAD_BYTES)
                ?: (intent.getIntExtra("test_payload_kib", 16).coerceIn(0, 256) * 1024),
            windowPackets = intent.getIntExtra("test_window_packets", 4).coerceIn(1, 64),
            readBufferBytes = intent.getIntExtra("test_read_buffer_kib", 64).coerceIn(1, 1024) * 1024,
            writeBufferBytes = intent.getIntExtra("test_write_buffer_kib", 0).coerceIn(0, 1024) * 1024,
            connectionPriority = connectionPriority,
            cocChannels = intent.getIntExtra("test_coc_channels", 1)
                .coerceIn(1, RawBenchmarkConfig.MAX_COC_CHANNELS),
            acknowledgeWindows = intent.getBooleanExtra("test_ack_windows", true),
            primeGattMtuForCoc = intent.getBooleanExtra("test_coc_prime_gatt_mtu", false),
            keepControlGattForCoc = intent.getBooleanExtra("test_coc_keep_control_gatt", true),
        )
        lateinit var active: BluetoothRawBenchmark
        active = BluetoothRawBenchmark(probeStatus(transport), {
            if (rawBenchmark === active) rawBenchmark = null
            finishRun()
        }, config)
        rawBenchmark = active
        if (experiment == "raw_rx") {
            active.startReceiver(activity, adapter, transport, phyPreference)
            return
        }
        when (transport) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> {
                val target = intent.getStringExtra("test_target")
                val device = target?.let { requested ->
                    adapter.bondedDevices.sortedBy { it.name ?: it.address }.firstOrNull { candidate ->
                        candidate.name?.contains(requested, ignoreCase = true) == true ||
                            candidate.address.equals(requested, ignoreCase = true)
                    }
                }
                if (device != null) {
                    active.startSender(activity, device, transport, durationSeconds, phyPreference)
                } else {
                    findRawReceiver(adapter, active, durationSeconds, transport, phyPreference)
                }
            }
            BluetoothTransportOption.BLE_L2CAP ->
                findRawReceiver(adapter, active, durationSeconds, transport, phyPreference)
            BluetoothTransportOption.BLE_GATT -> {
                onStatus("Use gatt_rx/gatt_tx for the GATT benchmark")
                active.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findRawReceiver(
        adapter: BluetoothAdapter,
        benchmark: BluetoothRawBenchmark,
        durationSeconds: Int,
        transport: BluetoothTransportOption,
        phyPreference: BlePhyPreference,
    ) {
        onStatus("RAW ${transport.shortLabel} TX scanning over BLE discovery")
        lateinit var discovery: BleL2capDiscovery
        discovery = BleL2capDiscovery(
            adapter,
            { peer ->
                activity.runOnUiThread {
                    if (discoveryScan !== discovery) return@runOnUiThread
                    discoveryScan = null
                    discovery.close()
                    benchmark.startSender(activity, peer.device, transport, durationSeconds, phyPreference)
                }
            },
            { count ->
                activity.runOnUiThread {
                    if (discoveryScan !== discovery) return@runOnUiThread
                    discoveryScan = null
                    if (count == 0) {
                        onStatus("RAW BLE benchmark receiver not found")
                        benchmark.close()
                    }
                }
            },
            { message ->
                activity.runOnUiThread {
                    if (discoveryScan !== discovery) return@runOnUiThread
                    discoveryScan = null
                    onStatus(message)
                    benchmark.close()
                }
            },
            { announcement ->
                announcement.streamToken == if (transport == BluetoothTransportOption.BLE_L2CAP) {
                    BluetoothRawBenchmark.DISCOVERY_TOKEN_BLE
                } else {
                    BluetoothRawBenchmark.DISCOVERY_TOKEN_CLASSIC
                } && announcement.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0
            },
        )
        discoveryScan = discovery
        discovery.start()
    }

    @SuppressLint("MissingPermission")
    private fun runGatt(
        adapter: BluetoothAdapter,
        experiment: String,
        durationSeconds: Int,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
        fencePackets: Int,
        advertiseTag: Int?,
        targetTag: Int?,
        rateKbps: Int,
    ) {
        lateinit var active: BleGattBenchmark
        active = BleGattBenchmark(activity, adapter, probeStatus(BluetoothTransportOption.BLE_GATT), {
            discoveryScan?.close()
            discoveryScan = null
            if (gattBenchmark === active) gattBenchmark = null
            finishRun()
        }, GattBenchmarkConfig(fencePackets, connectionPriority, advertiseTag, rateKbps = rateKbps))
        gattBenchmark = active
        when (experiment) {
            "gatt_rx" -> active.startReceiver(phyPreference)
            "gatt_notify_tx" -> active.startNotificationTransmitter(durationSeconds, phyPreference)
            "gatt_tx", "gatt_notify_rx" -> {
                onStatus(if (experiment == "gatt_tx") {
                    "GATT TX scanning for benchmark receiver"
                } else {
                    "GATT NOTIFY RX scanning for transmitter"
                })
                discoveryScan = BleGattBenchmark.findReceiver(
                    adapter,
                    { device ->
                        activity.runOnUiThread {
                            discoveryScan = null
                            if (experiment == "gatt_tx") {
                                active.startSender(device, durationSeconds, phyPreference)
                            } else {
                                active.startNotificationReceiver(device, phyPreference)
                            }
                        }
                    },
                    { message ->
                        activity.runOnUiThread {
                            onStatus(message)
                            active.close()
                        }
                    },
                    targetTag,
                )
            }
        }
    }

    /**
     * One phone receives and forwards at the same time over two GATT links, measuring how its
     * controller shares airtime. Mixed: peripheral upstream (the source writes), central
     * downstream. Central: central on both links (the source notifies upstream data).
     */
    @SuppressLint("MissingPermission")
    private fun runGattRelay(
        adapter: BluetoothAdapter,
        central: Boolean,
        durationSeconds: Int,
        phyPreference: BlePhyPreference,
        upstreamPriority: BleConnectionPriority,
        downstreamPriority: BleConnectionPriority,
        upstreamTag: Int,
        downstreamTag: Int,
        upstreamDelayMs: Long,
        downstreamRateKbps: Int,
    ) {
        val remaining = java.util.concurrent.atomic.AtomicInteger(2)
        val done = { if (remaining.decrementAndGet() == 0) finishRun() }
        val status = probeStatus(BluetoothTransportOption.BLE_GATT)
        val upstream = BleGattBenchmark(
            activity, adapter, status, done,
            GattBenchmarkConfig(connectionPriority = upstreamPriority, advertiseTag = upstreamTag.takeUnless { central }, label = "UP "),
        )
        val downstream = BleGattBenchmark(
            activity, adapter, status, done,
            GattBenchmarkConfig(connectionPriority = downstreamPriority, label = "DOWN ", rateKbps = downstreamRateKbps),
        )
        gattBenchmark = upstream
        relayDownstream = downstream
        onStatus(
            "GATT relay ${if (central) "central/central" else "peripheral/central"} probe starting; " +
                "priority up ${upstreamPriority.label} / down ${downstreamPriority.label}",
        )
        discoveryScan = BleGattBenchmark.findReceiver(
            adapter,
            { device -> activity.runOnUiThread { downstream.startSender(device, durationSeconds, phyPreference) } },
            { message -> activity.runOnUiThread { onStatus("DOWN $message"); downstream.close(); done() } },
            downstreamTag,
        )
        if (!central) {
            upstream.startReceiver(phyPreference)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (closed.get()) return@postDelayed
            relayUpstreamScan = BleGattBenchmark.findReceiver(
                adapter,
                { device -> activity.runOnUiThread { upstream.startNotificationReceiver(device, phyPreference) } },
                { message -> activity.runOnUiThread { onStatus("UP $message"); upstream.close(); done() } },
                upstreamTag,
            )
        }, upstreamDelayMs)
    }

    /** Saturating link results teach this phone's [LinkCapacity] store. */
    private fun probeStatus(transport: BluetoothTransportOption): (String) -> Unit = { message ->
        PROBE_RESULT.find(message)?.let { match ->
            val kbps = (match.groupValues[1].toDouble() * 1_000).toInt()
            if (kbps > 0) LinkCapacity.recordProbe(transport, kbps)
        }
        onStatus(message)
    }

    private fun finishRun() {
        if (!finished.compareAndSet(false, true)) return
        activity.runOnUiThread {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onFinished()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        discoveryScan?.close()
        discoveryScan = null
        rawBenchmark?.close()
        rawBenchmark = null
        gattBenchmark?.close()
        gattBenchmark = null
        relayUpstreamScan?.close()
        relayUpstreamScan = null
        relayDownstream?.close()
        relayDownstream = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun Intent.tag(name: String): Int? = getIntExtra(name, -1).takeIf { it in 0..255 }

    private companion object {
        val PROBE_RESULT = Regex("""^(?:GATT(?: NOTIFY)?|RAW .+?) (?:TX|RX)\S* FINAL ([0-9.]+) Mbps""")
    }
}
