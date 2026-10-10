using System.Buffers.Binary;
using System.Diagnostics;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Radios;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

const string ServiceUuid = "c8d5a100-9f88-4f65-a03d-7f5a2fb921fa";

if (args.Contains("--self-test"))
{
    RunCaptureOutputSelfTest();
    using var testIdentity = new WindowsVideoIdentity();
    SecureBluetoothProtocol.RunSelfTest(testIdentity);
    Console.WriteLine("Secure protocol self-test passed.");
    return 0;
}

var play = args.Contains("--play");
var outputArgument = Array.IndexOf(args, "--output");
if (outputArgument < 0 || outputArgument + 1 >= args.Length ||
    string.IsNullOrWhiteSpace(args[outputArgument + 1]) || args[outputArgument + 1].StartsWith("--", StringComparison.Ordinal))
{
    Console.Error.WriteLine("Capture requires --output <run-directory/capture.ivf> [--play].");
    return 1;
}
string outputPath;
try
{
    outputPath = ValidateCaptureOutput(args[outputArgument + 1]);
}
catch (Exception error) when (error is ArgumentException or IOException or InvalidOperationException)
{
    Console.Error.WriteLine($"Capture output rejected: {error.Message}");
    return 1;
}
Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);

var bluetoothRadio = (await Radio.GetRadiosAsync()).FirstOrDefault(radio => radio.Kind == RadioKind.Bluetooth);
if (!await SecureBluetoothProtocol.EnsureBluetoothOnAsync(bluetoothRadio))
{
    Console.Error.WriteLine($"Windows Bluetooth is {bluetoothRadio?.State.ToString() ?? "unavailable"} and could not be enabled. Turn it on in Settings and retry.");
    return 2;
}

var serviceId = RfcommServiceId.FromUuid(Guid.Parse(ServiceUuid));
using var identity = new WindowsVideoIdentity();
RfcommServiceProvider provider;
try
{
    provider = await RfcommServiceProvider.CreateAsync(serviceId);
}
catch (System.Runtime.InteropServices.COMException error) when ((uint)error.HResult == 0x800710DF)
{
    Console.Error.WriteLine("Windows reports that the Bluetooth radio is not ready. Turn Bluetooth off and on, then retry.");
    return 2;
}
var listener = new StreamSocketListener();
var completion = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
var captureStarted = 0;

listener.ConnectionReceived += async (_, eventArgs) =>
{
    if (Interlocked.CompareExchange(ref captureStarted, 1, 0) != 0)
    {
        eventArgs.Socket.Dispose();
        Console.Error.WriteLine("A capture is already active; another execution needs a fresh output.");
        return;
    }
    var exitCode = 0;
    Console.WriteLine($"Incoming RFCOMM connection from {eventArgs.Socket.Information.RemoteAddress.DisplayName}");
    try
    {
        await ReceiveAsync(eventArgs.Socket, outputPath, play, identity);
        Console.WriteLine("Capture finished; receiver will stop.");
    }
    catch (EndOfStreamException)
    {
        Console.WriteLine("Sender disconnected; capture preserved and receiver will stop.");
    }
    catch (Exception error)
    {
        Console.Error.WriteLine($"Connection failed: {error.GetType().Name}: {error.Message}");
        Console.Error.Flush();
        exitCode = 1;
    }
    finally
    {
        completion.TrySetResult(exitCode);
    }
};

await listener.BindServiceNameAsync(
    provider.ServiceId.AsString(),
    SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
provider.StartAdvertising(listener, true);
Console.WriteLine($"Advertising Bluetooth Video receiver {ServiceUuid}");
Console.WriteLine($"Application identity {SecureBluetoothProtocol.ShortNodeId(identity.NodeId)}; RFCOMM and video content require authenticated encryption.");
Console.WriteLine("Pair this laptop with the phone, then tap Connect and stream in the Android app.");

Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    completion.TrySetResult(130);
};

try
{
    return await completion.Task;
}
finally
{
    try
    {
        provider.StopAdvertising();
    }
    catch (System.Runtime.InteropServices.COMException error)
    {
        Console.Error.WriteLine($"Bluetooth advertisement was already unavailable during shutdown: 0x{error.HResult:X8}");
    }
    listener.Dispose();
}

static async Task ReceiveAsync(
    StreamSocket socket,
    string outputPath,
    bool play,
    WindowsVideoIdentity identity)
{
    using (socket)
    using (var input = new DataReader(socket.InputStream) { ByteOrder = ByteOrder.BigEndian })
    using (var writer = new DataWriter(socket.OutputStream) { ByteOrder = ByteOrder.BigEndian })
    using (var output = CreateCaptureFile(outputPath))
    using (var timestamps = new StreamWriter(CreateCaptureFile(Path.ChangeExtension(outputPath, ".timestamps.csv"))))
    {
        Process? player = null;
        try
        {
            await timestamps.WriteLineAsync("frame,presentation_time_us");
            var capabilities = new VideoNodeCapabilities(
                VideoNodeCapabilities.TransportClassic,
                0,
                VideoNodeCapabilities.CodecVp9 | VideoNodeCapabilities.CodecAv1,
                1100,
                0);
            using var link = await SecureBluetoothProtocol.RespondLinkAsync(
                size => ReadExactAsync(input, size),
                bytes => WriteExactAsync(writer, bytes),
                identity,
                capabilities);
            if ((link.PeerCapabilities.TransportMask & VideoNodeCapabilities.TransportClassic) == 0)
                throw new InvalidDataException("Sender did not authenticate Classic RFCOMM support.");
            using var content = await SecureBluetoothProtocol.RespondContentAsync(link, identity, capabilities);
            var contentHeader = await SecureBluetoothProtocol.ReadContentAsync(
                link,
                content,
                VideoContentType.StreamHeader);
            var header = contentHeader.Payload;
            if (header.Length != 11 || !header.AsSpan(0, 4).SequenceEqual("BTV2"u8))
                throw new InvalidDataException("Invalid encrypted stream header.");
            var width = BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(4, 2));
            var height = BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(6, 2));
            var fps = header[8];
            var codec = header[9];
            var rotationQuarterTurns = header[10];
            if (width is 0 or > 4096 || height is 0 or > 4096 || fps is 0 or > 120)
                throw new InvalidDataException($"Invalid video format {width}x{height} at {fps} FPS.");
            if (rotationQuarterTurns > 3)
                throw new InvalidDataException($"Invalid video rotation {rotationQuarterTurns}.");
            var codecMask = codec switch
            {
                2 => VideoNodeCapabilities.CodecVp9,
                3 => VideoNodeCapabilities.CodecAv1,
                _ => throw new InvalidDataException($"Unsupported codec ID {codec}."),
            };
            if ((content.PeerCapabilities.EncodeCodecMask & codecMask) == 0)
                throw new InvalidDataException("Source stream codec was not authenticated in its capabilities.");
            var fourCc = codec switch
            {
                2 => "VP90"u8.ToArray(),
                3 => "AV01"u8.ToArray(),
                _ => throw new InvalidDataException($"Unsupported codec ID {codec}."),
            };

            var ivfHeader = CreateIvfHeader(fourCc, width, height, fps);
            await output.WriteAsync(ivfHeader);
            player = play ? StartPlayer(rotationQuarterTurns) : null;
            var playerAvailable = player != null && await TryWritePlayerAsync(player, ivfHeader);
            var displayWidth = rotationQuarterTurns % 2 == 0 ? width : height;
            var displayHeight = rotationQuarterTurns % 2 == 0 ? height : width;
            Console.WriteLine(
                $"Authenticated link {SecureBluetoothProtocol.ShortNodeId(link.PeerNodeId)}; " +
                $"end-to-end source {SecureBluetoothProtocol.ShortNodeId(content.PeerNodeId)}.");
            Console.WriteLine($"Connected: end-to-end encrypted {(codec == 2 ? "VP9" : "AV1")} {displayWidth}x{displayHeight} at {fps} FPS");

            var stopwatch = Stopwatch.StartNew();
            long totalBytes = 0;
            var frames = 0;
            var playerHasKeyFrame = false;
            ulong lastIvfTimestamp = 0;
            ulong? firstPresentationTimeUs = null;
            while (true)
            {
                var contentPacket = await SecureBluetoothProtocol.ReadContentAsync(
                    link,
                    content,
                    VideoContentType.VideoPacket);
                if (contentPacket.Payload.Length < 13)
                    throw new InvalidDataException("Truncated encrypted video packet.");
                var packetHeader = contentPacket.Payload.AsSpan(0, 13);
                var payloadSize = BinaryPrimitives.ReadUInt32BigEndian(packetHeader.Slice(0, 4));
                var flags = packetHeader[4];
                var presentationTimeUs = BinaryPrimitives.ReadUInt64BigEndian(packetHeader.Slice(5, 8));
                if (payloadSize > 4 * 1024 * 1024)
                    throw new InvalidDataException($"Invalid payload size {payloadSize}.");
                if (contentPacket.Flags != flags || contentPacket.Payload.Length != 13 + payloadSize)
                    throw new InvalidDataException("Authenticated video packet metadata does not match its payload.");
                var payload = contentPacket.Payload.AsSpan(13).ToArray();
                if ((flags & 4) != 0)
                {
                    if (payload.Length != 1 || payload[0] > 3)
                        throw new InvalidDataException("Invalid display rotation packet.");
                    Console.WriteLine("Sender orientation changed. Restart the stream to rotate the Windows player.");
                    continue;
                }
                if ((flags & 1) != 0) continue;

                firstPresentationTimeUs ??= presentationTimeUs;
                var normalizedTimeUs = presentationTimeUs - firstPresentationTimeUs.Value;
                var ivfTimestamp = normalizedTimeUs * fps / 1_000_000;
                if (frames > 0 && ivfTimestamp <= lastIvfTimestamp)
                    ivfTimestamp = lastIvfTimestamp + 1;
                lastIvfTimestamp = ivfTimestamp;
                var frameHeader = new byte[12];
                BinaryPrimitives.WriteUInt32LittleEndian(frameHeader.AsSpan(0, 4), payloadSize);
                BinaryPrimitives.WriteUInt64LittleEndian(frameHeader.AsSpan(4, 8), ivfTimestamp);
                await output.WriteAsync(frameHeader);
                await output.WriteAsync(payload);
                await output.FlushAsync();
                await timestamps.WriteLineAsync($"{frames},{presentationTimeUs}");
                if (player != null && playerAvailable &&
                    (playerHasKeyFrame || (flags & 2) != 0))
                {
                    playerHasKeyFrame = true;
                    playerAvailable = await TryWritePlayerAsync(player, frameHeader, payload);
                }

                frames++;
                totalBytes += payload.Length;
                if (frames % Math.Max(fps * 5, 1) == 0)
                {
                    var kbps = totalBytes * 8 / stopwatch.Elapsed.TotalSeconds / 1000;
                    var mediaSeconds = normalizedTimeUs / 1_000_000.0;
                    var transportLag = stopwatch.Elapsed.TotalSeconds - mediaSeconds;
                    Console.WriteLine(
                        $"{frames} frames, {kbps:F0} kbps, media time {mediaSeconds:F1}s, " +
                        $"transport lag {transportLag:F2}s" +
                        ((flags & 2) != 0 ? ", keyframe" : ""));
                    await link.WriteAsync(VideoSecureRecordType.LinkHeartbeat, [1]);
                }
            }
        }
        finally
        {
            StopPlayer(player);
            player?.Dispose();
        }
    }
}

static FileStream CreateCaptureFile(string path) => new(path, FileMode.CreateNew, FileAccess.Write, FileShare.Read);

static string ValidateCaptureOutput(string requested)
{
    if (!Path.GetExtension(requested).Equals(".ivf", StringComparison.OrdinalIgnoreCase))
        throw new ArgumentException("Capture needs a plain .ivf filename.");
    var root = new DirectoryInfo(Environment.CurrentDirectory);
    while (root != null && !File.Exists(Path.Combine(root.FullName, "tools", "benchmark_layout.py")))
        root = root.Parent;
    if (root == null)
        throw new InvalidOperationException("Run from the project checkout.");
    var start = new ProcessStartInfo("python")
    {
        UseShellExecute = false, CreateNoWindow = true,
        RedirectStandardOutput = true, RedirectStandardError = true,
        StandardOutputEncoding = System.Text.Encoding.UTF8,
        StandardErrorEncoding = System.Text.Encoding.UTF8,
    };
    start.Environment["PYTHONIOENCODING"] = "utf-8";
    foreach (var argument in new[] { "-B", Path.Combine(root.FullName, "tools", "benchmark_layout.py"),
                                   "--group", "runs", "--path", requested })
        start.ArgumentList.Add(argument);
    using var guard = Process.Start(start)!;
    var path = guard.StandardOutput.ReadToEnd().Trim();
    var error = guard.StandardError.ReadToEnd().Trim();
    guard.WaitForExit();
    if (guard.ExitCode != 0)
        throw new InvalidOperationException(error);
    if (Path.GetDirectoryName(path)!.Equals(Path.Combine(root.FullName, "benchmarks", "runs"), StringComparison.OrdinalIgnoreCase))
        throw new InvalidOperationException("Capture needs an execution directory under benchmarks/runs.");
    foreach (var output in new[] { path, Path.ChangeExtension(path, ".timestamps.csv") })
        if (File.Exists(output) || Directory.Exists(output))
            throw new IOException($"Output already exists: {output}");
    return path;
}

static void RunCaptureOutputSelfTest()
{
    var directory = Path.Combine(Path.GetTempPath(), "bluetooth-receiver-self-test-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(directory);
    var capture = Path.Combine(directory, "capture.ivf");
    var timestamps = Path.ChangeExtension(capture, ".timestamps.csv");
    static void Blocked(Action action)
    {
        try { action(); }
        catch (Exception error) when (error is ArgumentException or IOException or InvalidOperationException) { return; }
        throw new Exception("Expected unsafe output to be rejected.");
    }
    try
    {
        ValidateCaptureOutput(capture);
        File.WriteAllText(capture, "original captured evidence");
        Blocked(() => { using var stream = CreateCaptureFile(capture); });
        if (File.ReadAllText(capture) != "original captured evidence")
            throw new Exception("Existing capture was changed.");
        Blocked(() => ValidateCaptureOutput(capture));
        File.Delete(capture);
        File.WriteAllText(timestamps, "original timing evidence");
        Blocked(() => ValidateCaptureOutput(capture));
        Blocked(() => { using var stream = CreateCaptureFile(timestamps); });
        if (File.ReadAllText(timestamps) != "original timing evidence")
            throw new Exception("Existing timestamps were changed.");
        File.Delete(timestamps);
        Blocked(() => ValidateCaptureOutput(Path.Combine(Environment.CurrentDirectory, "receiver", "capture.ivf")));
        Blocked(() => ValidateCaptureOutput(Path.Combine(directory, "capture:alternate.ivf")));
        Blocked(() => ValidateCaptureOutput(Path.Combine(directory, "capture.json")));
        using (var stream = CreateCaptureFile(ValidateCaptureOutput(capture)))
            stream.WriteByte(42);
        if (File.ReadAllBytes(capture) is not [42])
            throw new Exception("Fresh capture failed.");
        Console.WriteLine("Capture output self-test passed (8 cases; no Bluetooth).");
    }
    finally
    {
        File.Delete(capture);
        File.Delete(timestamps);
        Directory.Delete(directory);
    }
}

static async Task WriteExactAsync(DataWriter writer, byte[] bytes)
{
    writer.WriteBytes(bytes);
    await writer.StoreAsync();
}

static async Task<bool> TryWritePlayerAsync(Process player, params byte[][] chunks)
{
    try
    {
        foreach (var chunk in chunks)
            await player.StandardInput.BaseStream.WriteAsync(chunk);
        await player.StandardInput.BaseStream.FlushAsync();
        return true;
    }
    catch (Exception error) when (error is IOException or ObjectDisposedException)
    {
        Console.Error.WriteLine($"Live decoder stopped ({error.Message}); capture will continue.");
        StopPlayer(player);
        return false;
    }
}

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

static byte[] CreateIvfHeader(byte[] fourCc, ushort width, ushort height, byte fps)
{
    var header = new byte[32];
    "DKIF"u8.CopyTo(header);
    BinaryPrimitives.WriteUInt16LittleEndian(header.AsSpan(4, 2), 0);
    BinaryPrimitives.WriteUInt16LittleEndian(header.AsSpan(6, 2), 32);
    fourCc.CopyTo(header, 8);
    BinaryPrimitives.WriteUInt16LittleEndian(header.AsSpan(12, 2), width);
    BinaryPrimitives.WriteUInt16LittleEndian(header.AsSpan(14, 2), height);
    BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(16, 4), fps);
    BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(20, 4), 1);
    return header;
}

static void StopPlayer(Process? player)
{
    if (player == null) return;
    try
    {
        player.StandardInput.Close();
    }
    catch (Exception)
    {
        // The decoder may already have closed its input pipe.
    }

    try
    {
        if (!player.HasExited && !player.WaitForExit(2_000))
            player.Kill(entireProcessTree: true);
    }
    catch (InvalidOperationException)
    {
        // The decoder exited between the state check and cleanup.
    }
}

static Process StartPlayer(byte rotationQuarterTurns)
{
    try
    {
        var videoFilter = rotationQuarterTurns switch
        {
            1 => " -vf transpose=clock",
            2 => " -vf hflip,vflip",
            3 => " -vf transpose=cclock",
            _ => "",
        };
        var player = Process.Start(new ProcessStartInfo
        {
            FileName = "ffplay",
            Arguments = "-loglevel warning -window_title \"Bluetooth Camera\" " +
                $"-fflags nobuffer -flags low_delay -framedrop{videoFilter} -f ivf -i -",
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardError = true,
        }) ?? throw new InvalidOperationException("ffplay did not start.");
        player.ErrorDataReceived += (_, eventArgs) =>
        {
            if (eventArgs.Data != null) Console.Error.WriteLine($"ffplay: {eventArgs.Data}");
        };
        player.BeginErrorReadLine();
        return player;
    }
    catch (System.ComponentModel.Win32Exception)
    {
        throw new InvalidOperationException("ffplay was not found. Install FFmpeg or run without --play.");
    }
}
