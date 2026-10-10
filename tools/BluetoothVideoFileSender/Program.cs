using System.Buffers.Binary;
using System.Diagnostics;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Devices.Radios;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

const string ServiceUuid = "c8d5a100-9f88-4f65-a03d-7f5a2fb921fa";

if (args.Contains("--self-test"))
{
    using var testIdentity = new WindowsVideoIdentity();
    SecureBluetoothProtocol.RunSelfTest(testIdentity);
    Console.WriteLine("Secure protocol self-test passed.");
    return 0;
}

if (args.Length != 2 || string.IsNullOrWhiteSpace(args[1]) || args.Any(argument => argument.StartsWith("--", StringComparison.Ordinal)))
{
    Console.Error.WriteLine("Usage: BluetoothVideoFileSender <capture.ivf> <paired-device-name>");
    return 2;
}

var inputPath = Path.GetFullPath(args[0]);
var requestedName = args[1];
if (!File.Exists(inputPath))
{
    Console.Error.WriteLine($"Missing IVF input: {inputPath}");
    return 2;
}
var bluetoothRadio = (await Radio.GetRadiosAsync()).FirstOrDefault(radio => radio.Kind == RadioKind.Bluetooth);
if (!await SecureBluetoothProtocol.EnsureBluetoothOnAsync(bluetoothRadio))
{
    Console.Error.WriteLine($"Windows Bluetooth is {bluetoothRadio?.State.ToString() ?? "unavailable"} and could not be enabled. Turn it on in Settings and retry.");
    return 2;
}
var serviceId = RfcommServiceId.FromUuid(Guid.Parse(ServiceUuid));
var deviceInfos = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
var matches = deviceInfos.Where(info => info.Name.Equals(requestedName, StringComparison.OrdinalIgnoreCase)).ToArray();
if (matches.Length != 1)
{
    Console.Error.WriteLine($"Expected exactly one paired device named '{requestedName}'; found {matches.Length}.");
    return 2;
}
BluetoothDevice? device = null;
RfcommDeviceService? service = null;
foreach (var info in matches)
{
    var candidate = await BluetoothDevice.FromIdAsync(info.Id);
    if (candidate == null) continue;
    var services = await candidate.GetRfcommServicesForIdAsync(serviceId, BluetoothCacheMode.Uncached);
    service = services.Services.FirstOrDefault();
    if (service != null)
    {
        device = candidate;
        break;
    }
    candidate.Dispose();
}

if (device == null || service == null)
{
    Console.Error.WriteLine($"Paired device '{requestedName}' is not advertising Bluetooth Video {ServiceUuid}.");
    return 2;
}

using (device)
using (service)
using (var socket = new StreamSocket())
using (var file = File.OpenRead(inputPath))
using (var reader = new BinaryReader(file))
using (var identity = new WindowsVideoIdentity())
{
    Console.WriteLine($"Connecting to {device.Name} / RFCOMM service {service.ConnectionServiceName}...");
    try
    {
        await socket.ConnectAsync(
            service.ConnectionHostName,
            service.ConnectionServiceName,
            SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
    }
    catch (System.Runtime.InteropServices.COMException error) when ((uint)error.HResult == 0x80072743)
    {
        Console.Error.WriteLine(
            $"The paired device '{device.Name}' is unreachable over Classic RFCOMM. " +
            "Remove the stale Windows/Android bond, pair both endpoints again, start the Android receiver, and retry.");
        return 2;
    }

    var ivfHeader = reader.ReadBytes(32);
    if (ivfHeader.Length != 32 || !ivfHeader.AsSpan(0, 4).SequenceEqual("DKIF"u8))
        throw new InvalidDataException("Input is not an IVF file.");
    var fourCc = System.Text.Encoding.ASCII.GetString(ivfHeader, 8, 4);
    var codecId = fourCc switch
    {
        "VP90" => (byte)2,
        "AV01" => (byte)3,
        _ => throw new InvalidDataException($"Unsupported IVF codec {fourCc}."),
    };
    var width = BinaryPrimitives.ReadUInt16LittleEndian(ivfHeader.AsSpan(12, 2));
    var height = BinaryPrimitives.ReadUInt16LittleEndian(ivfHeader.AsSpan(14, 2));
    var rate = BinaryPrimitives.ReadUInt32LittleEndian(ivfHeader.AsSpan(16, 4));
    var scale = BinaryPrimitives.ReadUInt32LittleEndian(ivfHeader.AsSpan(20, 4));
    var fps = checked((byte)Math.Round(rate / (double)scale));

    using var input = new DataReader(socket.InputStream) { ByteOrder = ByteOrder.BigEndian };
    using var writer = new DataWriter(socket.OutputStream) { ByteOrder = ByteOrder.BigEndian };
    var codecMask = codecId == 2 ? VideoNodeCapabilities.CodecVp9 : VideoNodeCapabilities.CodecAv1;
    var capabilities = new VideoNodeCapabilities(
        VideoNodeCapabilities.TransportClassic,
        codecMask,
        0,
        1100,
        0);
    using var link = await SecureBluetoothProtocol.InitiateLinkAsync(
        size => ReadExactAsync(input, size),
        bytes => WriteExactAsync(writer, bytes),
        identity,
        capabilities);
    if ((link.PeerCapabilities.TransportMask & VideoNodeCapabilities.TransportClassic) == 0)
        throw new InvalidDataException("Receiver did not authenticate Classic RFCOMM support.");
    using var content = await SecureBluetoothProtocol.InitiateContentAsync(link, identity, capabilities);
    if ((content.PeerCapabilities.DecodeCodecMask & codecMask) == 0)
        throw new InvalidDataException($"Final receiver cannot decode {fourCc}.");

    var streamHeader = new byte[11];
    "BTV2"u8.CopyTo(streamHeader);
    BinaryPrimitives.WriteUInt16BigEndian(streamHeader.AsSpan(4, 2), width);
    BinaryPrimitives.WriteUInt16BigEndian(streamHeader.AsSpan(6, 2), height);
    streamHeader[8] = fps;
    streamHeader[9] = codecId;
    streamHeader[10] = 0; // IVF test files are already stored in display orientation.
    await SecureBluetoothProtocol.WriteContentAsync(
        link,
        content,
        VideoContentType.StreamHeader,
        0,
        streamHeader);
    Console.WriteLine(
        $"Authenticated link {SecureBluetoothProtocol.ShortNodeId(link.PeerNodeId)}; " +
        $"end-to-end receiver {SecureBluetoothProtocol.ShortNodeId(content.PeerNodeId)}.");

    var timer = Stopwatch.StartNew();
    ulong firstTimestamp = 0;
    var frames = 0;
    while (file.Position < file.Length)
    {
        var frameHeader = reader.ReadBytes(12);
        if (frameHeader.Length == 0) break;
        if (frameHeader.Length != 12) throw new EndOfStreamException("Truncated IVF frame header.");
        var size = BinaryPrimitives.ReadUInt32LittleEndian(frameHeader.AsSpan(0, 4));
        var timestamp = BinaryPrimitives.ReadUInt64LittleEndian(frameHeader.AsSpan(4, 8));
        if (frames == 0) firstTimestamp = timestamp;
        var relativeTimestamp = timestamp - firstTimestamp;
        var target = TimeSpan.FromSeconds(relativeTimestamp * scale / (double)rate);
        var delay = target - timer.Elapsed;
        if (delay > TimeSpan.Zero) await Task.Delay(delay);
        var payload = reader.ReadBytes(checked((int)size));
        if (payload.Length != size) throw new EndOfStreamException("Truncated IVF frame.");

        var flags = frames == 0 ? (byte)2 : (byte)0;
        var packet = new byte[13 + payload.Length];
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(0, 4), size);
        packet[4] = flags;
        BinaryPrimitives.WriteUInt64BigEndian(
            packet.AsSpan(5, 8),
            (ulong)(target.TotalMilliseconds * 1_000));
        payload.CopyTo(packet, 13);
        await SecureBluetoothProtocol.WriteContentAsync(
            link,
            content,
            VideoContentType.VideoPacket,
            flags,
            packet);
        frames++;
    }
    Console.WriteLine($"Sent {frames} end-to-end encrypted {fourCc} frames at {width}x{height}/{fps} FPS in {timer.Elapsed.TotalSeconds:F1}s.");
}

return 0;

static async Task<byte[]> ReadExactAsync(DataReader reader, int size)
{
    var result = new byte[size];
    var position = 0;
    reader.InputStreamOptions = InputStreamOptions.Partial;
    while (position < size)
    {
        var loaded = await reader.LoadAsync((uint)(size - position));
        if (loaded == 0) throw new EndOfStreamException("Bluetooth connection closed.");
        var chunk = new byte[loaded];
        reader.ReadBytes(chunk);
        chunk.CopyTo(result, position);
        position += chunk.Length;
    }
    return result;
}

static async Task WriteExactAsync(DataWriter writer, byte[] bytes)
{
    writer.WriteBytes(bytes);
    await writer.StoreAsync();
}
