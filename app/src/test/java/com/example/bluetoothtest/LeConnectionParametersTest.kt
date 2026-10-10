package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattServerCallback
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LE connection parameters (LeConnectionParameters.kt): the hidden framework callbacks are the only
 * way to see each link's connection interval without HCI snoop, so every GATT callback this app
 * registers must declare them with the exact framework signature, and the log line must convert the
 * Core-spec units correctly.
 */
class LeConnectionParametersTest {
    @Test
    fun unitsFollowTheCoreSpecification() {
        val parameters = LeConnectionParameters(intervalUnits = 24, peripheralLatency = 0, supervisionTimeoutUnits = 500, status = 0)
        assertEquals(30.0, parameters.intervalMs, 1e-9)
        assertEquals(5_000, parameters.supervisionTimeoutMs)
    }

    @Test
    fun onePduPerEventBoundsMatchTheMeasuredSlowLink() {
        // One 251-octet PDU (247 L2CAP payload bytes) per 30 ms event is 65 kbps: the ASUS <-> A15s
        // links carried about 65 kbps on GATT and CoC (mesh4-57484598).
        assertEquals(65, LeConnectionParameters(24, 0, 500, 0).payloadKbpsAt(1))
        assertEquals(131, LeConnectionParameters(12, 0, 500, 0).payloadKbpsAt(1))
        assertEquals(263, LeConnectionParameters(12, 0, 500, 0).payloadKbpsAt(2))
        assertEquals(0, LeConnectionParameters(0, 0, 500, 0).payloadKbpsAt(1))
    }

    @Test
    fun logLineCarriesRoleIntervalAndBounds() {
        val line = leConnectionParametersLine(
            "ble-control",
            LeLinkRole.PERIPHERAL,
            "AA:BB:CC:DD:EE:FF",
            LeConnectionParameters(9, 0, 500, 0),
        )
        assertEquals(
            "BENCHMARK le_conn_params: link=ble-control | role=peripheral | peer=...EE:FF | intervalMs=11.25 | " +
                "latency=0 | supervisionMs=5000 | status=0 | bound1PduKbps=175 | bound2PduKbps=351",
            line,
        )
    }

    @Test
    fun everyGattCallbackDeclaresTheHiddenConnectionUpdateMethod() {
        val int = Int::class.javaPrimitiveType!!
        val clients = appClasses().filter { it != BluetoothGattCallback::class.java && BluetoothGattCallback::class.java.isAssignableFrom(it) }
        val servers = appClasses().filter {
            it != BluetoothGattServerCallback::class.java && BluetoothGattServerCallback::class.java.isAssignableFrom(it)
        }
        // GattVideoClient and BleL2capPsmReader (clients); GattVideoServer and BleL2capControlServer (servers).
        assertTrue("client callbacks: $clients", clients.size >= 2)
        assertTrue("server callbacks: $servers", servers.size >= 2)
        clients.forEach { type ->
            val method = type.getDeclaredMethod("onConnectionUpdated", BluetoothGatt::class.java, int, int, int, int)
            assertTrue("$type must declare a public instance method", Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers))
            assertEquals(Void.TYPE, method.returnType)
        }
        servers.forEach { type ->
            val method = type.getDeclaredMethod("onConnectionUpdated", BluetoothDevice::class.java, int, int, int, int)
            assertTrue("$type must declare a public instance method", Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers))
            assertEquals(Void.TYPE, method.returnType)
        }
    }

    @Test
    fun cocControlPhyIsSequencedByDefault() {
        assertTrue(DebugTunables.cocSequencedPhy)
    }

    /** Every class of the app package on the test classpath, whether compiled to a directory or a JAR. */
    private fun appClasses(): List<Class<*>> {
        val loader = javaClass.classLoader!!
        val prefix = "com/example/bluetoothtest/"
        val anchor = loader.getResource(prefix + "GattVideoServer.class")!!
        val names = if (anchor.protocol == "jar") {
            (anchor.openConnection() as JarURLConnection).jarFile.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(".class") && '/' !in it.removePrefix(prefix) }
                .map { it.removePrefix(prefix) }
                .toList()
        } else {
            File(anchor.toURI()).parentFile.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".class") }
        }
        return names.mapNotNull { name ->
            runCatching { Class.forName("com.example.bluetoothtest." + name.removeSuffix(".class"), false, loader) }
                .getOrNull()
        }
    }
}
