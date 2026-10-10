using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Windows.Devices.Radios;

internal readonly record struct VideoNodeCapabilities(
    byte TransportMask,
    byte EncodeCodecMask,
    byte DecodeCodecMask,
    ushort MaxBitrateKbps,
    byte MaxChildren)
{
    internal const byte TransportClassic = 1;
    internal const byte CodecVp9 = 1;
    internal const byte CodecAv1 = 2;
}

internal enum VideoSecureRecordType : byte
{
    EndToEndHandshake = 4,
    EndToEndContent = 5,
    LinkHeartbeat = 6,
}

internal enum VideoContentType : byte
{
    StreamHeader = 1,
    VideoPacket = 2,
    StreamState = 4,
    KeyFrameRequest = 5,
}

internal sealed record VideoSecureRecord(VideoSecureRecordType Type, byte[] Payload);
internal sealed record VideoContentRecord(VideoContentType Type, byte Flags, byte[] Payload);

internal sealed class WindowsVideoIdentity : IDisposable
{
    private const string KeyName = "BluetoothVideoMeshIdentityV1";
    private readonly ECDsa ecdsa;

    internal WindowsVideoIdentity()
    {
        var provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
        CngKey key;
        if (CngKey.Exists(KeyName, provider, CngKeyOpenOptions.UserKey))
        {
            key = CngKey.Open(KeyName, provider, CngKeyOpenOptions.UserKey);
        }
        else
        {
            key = CngKey.Create(
                CngAlgorithm.ECDsaP256,
                KeyName,
                new CngKeyCreationParameters
                {
                    Provider = provider,
                    KeyUsage = CngKeyUsages.Signing,
                    ExportPolicy = CngExportPolicies.None,
                });
        }
        using (key)
        {
            ecdsa = new ECDsaCng(key);
        }
        PublicKey = ecdsa.ExportSubjectPublicKeyInfo();
        NodeId = SHA256.HashData(PublicKey)[..16];
    }

    internal byte[] PublicKey { get; }
    internal byte[] NodeId { get; }

    internal byte[] Sign(byte[] message)
    {
        lock (ecdsa)
        {
            return ecdsa.SignData(
                message,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence);
        }
    }

    public void Dispose() => ecdsa.Dispose();
}

internal sealed class VideoSecureChannel : IDisposable
{
    private readonly Func<int, Task<byte[]>> readExact;
    private readonly Func<byte[], Task> writeExact;
    private readonly byte[] sendKey;
    private readonly byte[] receiveKey;
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private long sendSequence;
    private long receiveSequence;

    internal VideoSecureChannel(
        byte[] peerNodeId,
        VideoNodeCapabilities peerCapabilities,
        byte[] sendKey,
        byte[] receiveKey,
        Func<int, Task<byte[]>> readExact,
        Func<byte[], Task> writeExact)
    {
        PeerNodeId = peerNodeId;
        PeerCapabilities = peerCapabilities;
        this.sendKey = sendKey;
        this.receiveKey = receiveKey;
        this.readExact = readExact;
        this.writeExact = writeExact;
    }

    internal byte[] PeerNodeId { get; }
    internal VideoNodeCapabilities PeerCapabilities { get; }

    internal async Task WriteAsync(VideoSecureRecordType type, byte[] payload)
    {
        if (payload.Length > SecureBluetoothProtocol.MaxSecurePlaintextBytes)
            throw new InvalidDataException("Secure record is too large.");
        await sendLock.WaitAsync();
        try
        {
            var header = SecureBluetoothProtocol.EncodeSecureHeader(
                type,
                sendSequence,
                checked(payload.Length + SecureBluetoothProtocol.TagBytes));
            var ciphertext = SecureBluetoothProtocol.Encrypt(sendKey, sendSequence, payload, header);
            sendSequence++;
            await writeExact(Combine(header, ciphertext));
        }
        finally
        {
            sendLock.Release();
        }
    }

    internal async Task<VideoSecureRecord> ReadAsync()
    {
        var header = await readExact(SecureBluetoothProtocol.SecureHeaderBytes);
        var metadata = SecureBluetoothProtocol.DecodeSecureHeader(header);
        if (metadata.Sequence != receiveSequence)
            throw new InvalidDataException(
                $"Unexpected secure record sequence {metadata.Sequence}; expected {receiveSequence}.");
        var ciphertext = await readExact(metadata.CiphertextBytes);
        var plaintext = SecureBluetoothProtocol.Decrypt(
            receiveKey,
            metadata.Sequence,
            ciphertext,
            header);
        receiveSequence++;
        return new VideoSecureRecord(metadata.Type, plaintext);
    }

    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(sendKey);
        CryptographicOperations.ZeroMemory(receiveKey);
        sendLock.Dispose();
    }

    private static byte[] Combine(byte[] first, byte[] second)
    {
        var result = new byte[first.Length + second.Length];
        first.CopyTo(result, 0);
        second.CopyTo(result, first.Length);
        return result;
    }
}

internal sealed class VideoContentChannel : IDisposable
{
    private readonly byte[] sendKey;
    private readonly byte[] receiveKey;
    private long sendSequence;
    private long receiveSequence;

    internal VideoContentChannel(
        byte[] peerNodeId,
        VideoNodeCapabilities peerCapabilities,
        byte[] sendKey,
        byte[] receiveKey)
    {
        PeerNodeId = peerNodeId;
        PeerCapabilities = peerCapabilities;
        this.sendKey = sendKey;
        this.receiveKey = receiveKey;
    }

    internal byte[] PeerNodeId { get; }
    internal VideoNodeCapabilities PeerCapabilities { get; }

    internal byte[] Seal(VideoContentType type, byte flags, byte[] payload)
    {
        if (payload.Length > SecureBluetoothProtocol.MaxContentPlaintextBytes)
            throw new InvalidDataException("End-to-end content is too large.");
        var header = SecureBluetoothProtocol.EncodeContentHeader(
            type,
            sendSequence,
            flags,
            checked(payload.Length + SecureBluetoothProtocol.TagBytes));
        var ciphertext = SecureBluetoothProtocol.Encrypt(sendKey, sendSequence, payload, header);
        sendSequence++;
        var result = new byte[header.Length + ciphertext.Length];
        header.CopyTo(result, 0);
        ciphertext.CopyTo(result, header.Length);
        return result;
    }

    internal VideoContentRecord Open(byte[] encoded)
    {
        var metadata = SecureBluetoothProtocol.DecodeContentHeader(encoded);
        if (metadata.Sequence != receiveSequence)
            throw new InvalidDataException(
                $"Unexpected end-to-end sequence {metadata.Sequence}; expected {receiveSequence}.");
        var header = encoded[..SecureBluetoothProtocol.ContentHeaderBytes];
        var plaintext = SecureBluetoothProtocol.Decrypt(
            receiveKey,
            metadata.Sequence,
            encoded[SecureBluetoothProtocol.ContentHeaderBytes..],
            header);
        receiveSequence++;
        return new VideoContentRecord(metadata.Type, metadata.Flags, plaintext);
    }

    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(sendKey);
        CryptographicOperations.ZeroMemory(receiveKey);
    }
}

internal static class SecureBluetoothProtocol
{
    internal const int TagBytes = 16;
    internal const int SecureHeaderBytes = 17;
    internal const int ContentHeaderBytes = 18;
    internal const int MaxSecurePlaintextBytes = 4 * 1024 * 1024 + 128;
    internal const int MaxContentPlaintextBytes = 4 * 1024 * 1024 + 13;
    private const int NonceBytes = 12;
    private const int HelloNonceBytes = 32;
    private const int MaxHelloBytes = 2048;
    private static readonly byte[] LinkSignatureContext =
        Encoding.ASCII.GetBytes("BluetoothVideoMesh/LinkHandshake/1");
    private static readonly byte[] LinkKeyContext =
        Encoding.ASCII.GetBytes("BluetoothVideoMesh/LinkSession/1");
    private static readonly byte[] ContentSignatureContext =
        Encoding.ASCII.GetBytes("BluetoothVideoMesh/EndToEndHandshake/1");
    private static readonly byte[] ContentKeyContext =
        Encoding.ASCII.GetBytes("BluetoothVideoMesh/EndToEndSession/1");

    internal static async Task<bool> EnsureBluetoothOnAsync(Radio? radio)
    {
        if (radio == null) return false;
        if (radio.State == RadioState.On) return true;
        if (await Radio.RequestAccessAsync() != RadioAccessStatus.Allowed) return false;
        if (await radio.SetStateAsync(RadioState.On) != RadioAccessStatus.Allowed) return false;
        for (var attempt = 0; attempt < 20 && radio.State != RadioState.On; attempt++)
            await Task.Delay(250);
        return radio.State == RadioState.On;
    }

    internal static Task<VideoSecureChannel> InitiateLinkAsync(
        Func<int, Task<byte[]>> readExact,
        Func<byte[], Task> writeExact,
        WindowsVideoIdentity identity,
        VideoNodeCapabilities capabilities) =>
        EstablishLinkAsync(true, readExact, writeExact, identity, capabilities);

    internal static Task<VideoSecureChannel> RespondLinkAsync(
        Func<int, Task<byte[]>> readExact,
        Func<byte[], Task> writeExact,
        WindowsVideoIdentity identity,
        VideoNodeCapabilities capabilities) =>
        EstablishLinkAsync(false, readExact, writeExact, identity, capabilities);

    internal static async Task<VideoContentChannel> InitiateContentAsync(
        VideoSecureChannel link,
        WindowsVideoIdentity identity,
        VideoNodeCapabilities capabilities)
    {
        using var pending = CreateHello(identity, 1, capabilities, true);
        await link.WriteAsync(VideoSecureRecordType.EndToEndHandshake, EncodeHello(pending.Hello));
        var record = await link.ReadAsync();
        if (record.Type != VideoSecureRecordType.EndToEndHandshake)
            throw new InvalidDataException("Expected an end-to-end handshake response.");
        var remote = DecodeHello(record.Payload);
        if (remote.Role != 2) throw new InvalidDataException("Expected an end-to-end responder.");
        var keys = FinishHandshake(pending, remote, true);
        return new VideoContentChannel(remote.NodeId, remote.Capabilities, keys.Send, keys.Receive);
    }

    internal static async Task<VideoContentChannel> RespondContentAsync(
        VideoSecureChannel link,
        WindowsVideoIdentity identity,
        VideoNodeCapabilities capabilities)
    {
        var record = await link.ReadAsync();
        if (record.Type != VideoSecureRecordType.EndToEndHandshake)
            throw new InvalidDataException("Expected an end-to-end handshake request.");
        var remote = DecodeHello(record.Payload);
        if (remote.Role != 1) throw new InvalidDataException("Expected an end-to-end initiator.");
        using var pending = CreateHello(identity, 2, capabilities, true);
        var keys = FinishHandshake(pending, remote, true);
        await link.WriteAsync(VideoSecureRecordType.EndToEndHandshake, EncodeHello(pending.Hello));
        return new VideoContentChannel(remote.NodeId, remote.Capabilities, keys.Send, keys.Receive);
    }

    internal static async Task WriteContentAsync(
        VideoSecureChannel link,
        VideoContentChannel content,
        VideoContentType type,
        byte flags,
        byte[] payload) =>
        await link.WriteAsync(VideoSecureRecordType.EndToEndContent, content.Seal(type, flags, payload));

    internal static async Task<VideoContentRecord> ReadContentAsync(
        VideoSecureChannel link,
        VideoContentChannel content,
        VideoContentType expectedType)
    {
        var record = await link.ReadAsync();
        if (record.Type != VideoSecureRecordType.EndToEndContent)
            throw new InvalidDataException("Expected end-to-end content.");
        var opened = content.Open(record.Payload);
        if (opened.Type != expectedType)
            throw new InvalidDataException($"Expected {expectedType}, received {opened.Type}.");
        return opened;
    }

    internal static string ShortNodeId(byte[] nodeId) =>
        Convert.ToHexString(nodeId).ToLowerInvariant()[..12];

    internal static void RunSelfTest(WindowsVideoIdentity identity)
    {
        var key = Enumerable.Range(0, 32).Select(value => (byte)value).ToArray();
        var plaintext = Encoding.ASCII.GetBytes("cross-platform");
        var associatedData = Encoding.ASCII.GetBytes("BMS1-test-aad");
        var ciphertext = Encrypt(key, 7, plaintext, associatedData);
        const string expectedCiphertext = "831F7C6C643A2E96A52B8940413960B7E55624A70B6B69866E3DF1D44810";
        if (Convert.ToHexString(ciphertext) != expectedCiphertext ||
            !Decrypt(key, 7, ciphertext, associatedData).AsSpan().SequenceEqual(plaintext))
            throw new CryptographicException("AES-GCM interoperability vector failed.");
        var modified = ciphertext.ToArray();
        modified[^1] ^= 1;
        try
        {
            Decrypt(key, 7, modified, associatedData);
            throw new CryptographicException("Modified AES-GCM content was accepted.");
        }
        catch (AuthenticationTagMismatchException)
        {
            // Expected.
        }

        using var hello = CreateHello(
            identity,
            1,
            new VideoNodeCapabilities(VideoNodeCapabilities.TransportClassic, 3, 3, 1100, 0),
            false);
        VerifyHello(DecodeHello(EncodeHello(hello.Hello)), false);
        try
        {
            VerifyHello(hello.Hello, true);
            throw new CryptographicException("Link handshake was accepted as an end-to-end handshake.");
        }
        catch (CryptographicException error) when (error.Message == "Handshake signature verification failed.")
        {
            // Expected domain separation.
        }
        CryptographicOperations.ZeroMemory(key);
    }

    private static async Task<VideoSecureChannel> EstablishLinkAsync(
        bool initiate,
        Func<int, Task<byte[]>> readExact,
        Func<byte[], Task> writeExact,
        WindowsVideoIdentity identity,
        VideoNodeCapabilities capabilities)
    {
        if (initiate)
        {
            using var pending = CreateHello(identity, 1, capabilities, false);
            await WriteHelloAsync(writeExact, pending.Hello);
            var remote = await ReadHelloAsync(readExact);
            if (remote.Role != 2) throw new InvalidDataException("Expected a link responder.");
            var keys = FinishHandshake(pending, remote, false);
            return new VideoSecureChannel(
                remote.NodeId, remote.Capabilities, keys.Send, keys.Receive, readExact, writeExact);
        }
        else
        {
            var remote = await ReadHelloAsync(readExact);
            if (remote.Role != 1) throw new InvalidDataException("Expected a link initiator.");
            using var pending = CreateHello(identity, 2, capabilities, false);
            var keys = FinishHandshake(pending, remote, false);
            await WriteHelloAsync(writeExact, pending.Hello);
            return new VideoSecureChannel(
                remote.NodeId, remote.Capabilities, keys.Send, keys.Receive, readExact, writeExact);
        }
    }

    private static PendingHello CreateHello(
        WindowsVideoIdentity identity,
        byte role,
        VideoNodeCapabilities capabilities,
        bool endToEnd)
    {
        var ephemeral = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        var hello = new HandshakeHello(
            role,
            identity.NodeId,
            identity.PublicKey,
            ephemeral.ExportSubjectPublicKeyInfo(),
            RandomNumberGenerator.GetBytes(HelloNonceBytes),
            capabilities,
            []);
        var context = endToEnd ? ContentSignatureContext : LinkSignatureContext;
        hello = hello with { Signature = identity.Sign(Combine(context, EncodeUnsignedHello(hello))) };
        return new PendingHello(hello, ephemeral, endToEnd);
    }

    private static SessionKeys FinishHandshake(PendingHello local, HandshakeHello remote, bool endToEnd)
    {
        if (local.EndToEnd != endToEnd)
            throw new InvalidOperationException("Handshake purpose mismatch.");
        if (local.Hello.Role == remote.Role)
            throw new InvalidDataException("Handshake peers must have opposite roles.");
        VerifyHello(remote, endToEnd);
        using var remoteEphemeral = ECDiffieHellman.Create();
        remoteEphemeral.ImportSubjectPublicKeyInfo(remote.EphemeralPublicKey, out _);
        var sharedSecret = local.Ephemeral.DeriveRawSecretAgreement(remoteEphemeral.PublicKey);
        var initiator = local.Hello.Role == 1 ? local.Hello : remote;
        var responder = local.Hello.Role == 2 ? local.Hello : remote;
        var transcript = Combine(EncodeHello(initiator), EncodeHello(responder));
        var salt = SHA256.HashData(Combine(initiator.Nonce, responder.Nonce));
        var context = endToEnd ? ContentKeyContext : LinkKeyContext;
        var material = HkdfSha256(
            sharedSecret,
            salt,
            Combine(context, SHA256.HashData(transcript)),
            64);
        CryptographicOperations.ZeroMemory(sharedSecret);
        var initiatorToResponder = material[..32];
        var responderToInitiator = material[32..];
        CryptographicOperations.ZeroMemory(material);
        return local.Hello.Role == 1
            ? new SessionKeys(initiatorToResponder, responderToInitiator)
            : new SessionKeys(responderToInitiator, initiatorToResponder);
    }

    private static void VerifyHello(HandshakeHello hello, bool endToEnd)
    {
        if (hello.Role is not (1 or 2) || hello.Nonce.Length != HelloNonceBytes)
            throw new InvalidDataException("Invalid handshake hello.");
        if (!CryptographicOperations.FixedTimeEquals(SHA256.HashData(hello.IdentityPublicKey)[..16], hello.NodeId))
            throw new CryptographicException("Node ID does not match its identity key.");
        using var identity = ECDsa.Create();
        identity.ImportSubjectPublicKeyInfo(hello.IdentityPublicKey, out _);
        var context = endToEnd ? ContentSignatureContext : LinkSignatureContext;
        if (!identity.VerifyData(
                Combine(context, EncodeUnsignedHello(hello with { Signature = [] })),
                hello.Signature,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence))
            throw new CryptographicException("Handshake signature verification failed.");
    }

    private static async Task WriteHelloAsync(Func<byte[], Task> writeExact, HandshakeHello hello)
    {
        var encoded = EncodeHello(hello);
        if (encoded.Length > MaxHelloBytes) throw new InvalidDataException("Handshake is too large.");
        var framed = new byte[4 + encoded.Length];
        BinaryPrimitives.WriteInt32BigEndian(framed.AsSpan(0, 4), encoded.Length);
        encoded.CopyTo(framed, 4);
        await writeExact(framed);
    }

    private static async Task<HandshakeHello> ReadHelloAsync(Func<int, Task<byte[]>> readExact)
    {
        var size = BinaryPrimitives.ReadInt32BigEndian(await readExact(4));
        if (size is < 1 or > MaxHelloBytes) throw new InvalidDataException("Invalid handshake size.");
        return DecodeHello(await readExact(size));
    }

    private static byte[] EncodeHello(HandshakeHello hello)
    {
        var unsigned = EncodeUnsignedHello(hello);
        if (hello.Signature.Length is < 1 or > 256)
            throw new InvalidDataException("Invalid handshake signature length.");
        using var output = new MemoryStream();
        output.Write(unsigned);
        WriteUInt16(output, checked((ushort)hello.Signature.Length));
        output.Write(hello.Signature);
        return output.ToArray();
    }

    private static byte[] EncodeUnsignedHello(HandshakeHello hello)
    {
        if (hello.IdentityPublicKey.Length is < 1 or > 512 ||
            hello.EphemeralPublicKey.Length is < 1 or > 512 ||
            hello.NodeId.Length != 16 || hello.Nonce.Length != HelloNonceBytes)
            throw new InvalidDataException("Invalid handshake key material.");
        using var output = new MemoryStream();
        output.Write("BMH2"u8);
        output.WriteByte(hello.Role);
        output.Write(hello.NodeId);
        WriteUInt16(output, checked((ushort)hello.IdentityPublicKey.Length));
        output.Write(hello.IdentityPublicKey);
        WriteUInt16(output, checked((ushort)hello.EphemeralPublicKey.Length));
        output.Write(hello.EphemeralPublicKey);
        output.Write(hello.Nonce);
        output.Write(EncodeCapabilities(hello.Capabilities));
        return output.ToArray();
    }

    private static HandshakeHello DecodeHello(byte[] bytes)
    {
        var offset = 0;
        byte[] Read(int count)
        {
            if (count < 0 || offset + count > bytes.Length)
                throw new EndOfStreamException("Truncated handshake.");
            var result = bytes.AsSpan(offset, count).ToArray();
            offset += count;
            return result;
        }
        ushort ReadUInt16() => BinaryPrimitives.ReadUInt16BigEndian(Read(2));

        if (!Read(4).AsSpan().SequenceEqual("BMH2"u8))
            throw new InvalidDataException("Invalid handshake magic.");
        var role = Read(1)[0];
        var nodeId = Read(16);
        var identityKeyLength = ReadUInt16();
        if (identityKeyLength is < 1 or > 512) throw new InvalidDataException("Invalid identity key length.");
        var identityKey = Read(identityKeyLength);
        var ephemeralKeyLength = ReadUInt16();
        if (ephemeralKeyLength is < 1 or > 512) throw new InvalidDataException("Invalid ephemeral key length.");
        var ephemeralKey = Read(ephemeralKeyLength);
        var nonce = Read(HelloNonceBytes);
        var capabilities = DecodeCapabilities(Read(7));
        var signatureLength = ReadUInt16();
        if (signatureLength is < 1 or > 256) throw new InvalidDataException("Invalid signature length.");
        var signature = Read(signatureLength);
        if (offset != bytes.Length) throw new InvalidDataException("Trailing handshake data.");
        return new HandshakeHello(role, nodeId, identityKey, ephemeralKey, nonce, capabilities, signature);
    }

    private static byte[] EncodeCapabilities(VideoNodeCapabilities capabilities)
    {
        var bytes = new byte[7];
        bytes[0] = 1;
        bytes[1] = capabilities.TransportMask;
        bytes[2] = capabilities.EncodeCodecMask;
        bytes[3] = capabilities.DecodeCodecMask;
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(4, 2), capabilities.MaxBitrateKbps);
        bytes[6] = capabilities.MaxChildren;
        return bytes;
    }

    private static VideoNodeCapabilities DecodeCapabilities(byte[] bytes)
    {
        if (bytes.Length != 7 || bytes[0] != 1)
            throw new InvalidDataException("Unsupported capability message.");
        return new VideoNodeCapabilities(
            bytes[1],
            bytes[2],
            bytes[3],
            BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(4, 2)),
            bytes[6]);
    }

    internal static byte[] EncodeSecureHeader(
        VideoSecureRecordType type,
        long sequence,
        int ciphertextBytes)
    {
        var header = new byte[SecureHeaderBytes];
        "BMS1"u8.CopyTo(header);
        header[4] = (byte)type;
        BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(5, 8), sequence);
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(13, 4), ciphertextBytes);
        return header;
    }

    internal static (VideoSecureRecordType Type, long Sequence, int CiphertextBytes) DecodeSecureHeader(byte[] header)
    {
        if (header.Length != SecureHeaderBytes || !header.AsSpan(0, 4).SequenceEqual("BMS1"u8))
            throw new InvalidDataException("Invalid secure record header.");
        var type = (VideoSecureRecordType)header[4];
        if (type is not (VideoSecureRecordType.EndToEndHandshake or
            VideoSecureRecordType.EndToEndContent or VideoSecureRecordType.LinkHeartbeat))
            throw new InvalidDataException($"Unknown secure record type {header[4]}.");
        var sequence = BinaryPrimitives.ReadInt64BigEndian(header.AsSpan(5, 8));
        var size = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(13, 4));
        if (sequence < 0 || size is < TagBytes or > MaxSecurePlaintextBytes + TagBytes)
            throw new InvalidDataException("Invalid secure record metadata.");
        return (type, sequence, size);
    }

    internal static byte[] EncodeContentHeader(
        VideoContentType type,
        long sequence,
        byte flags,
        int ciphertextBytes)
    {
        var header = new byte[ContentHeaderBytes];
        "BME1"u8.CopyTo(header);
        header[4] = (byte)type;
        BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(5, 8), sequence);
        header[13] = flags;
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(14, 4), ciphertextBytes);
        return header;
    }

    internal static (VideoContentType Type, long Sequence, byte Flags, int CiphertextBytes)
        DecodeContentHeader(byte[] encoded)
    {
        if (encoded.Length < ContentHeaderBytes || !encoded.AsSpan(0, 4).SequenceEqual("BME1"u8))
            throw new InvalidDataException("Invalid end-to-end content header.");
        var type = (VideoContentType)encoded[4];
        if (type is not (VideoContentType.StreamHeader or VideoContentType.VideoPacket or
            VideoContentType.StreamState or VideoContentType.KeyFrameRequest))
            throw new InvalidDataException($"Unknown end-to-end content type {encoded[4]}.");
        var sequence = BinaryPrimitives.ReadInt64BigEndian(encoded.AsSpan(5, 8));
        var size = BinaryPrimitives.ReadInt32BigEndian(encoded.AsSpan(14, 4));
        if (sequence < 0 || size is < TagBytes or > MaxContentPlaintextBytes + TagBytes ||
            encoded.Length != ContentHeaderBytes + size)
            throw new InvalidDataException("Invalid end-to-end content metadata.");
        if (type != VideoContentType.VideoPacket && encoded[13] != 0)
            throw new InvalidDataException("Non-video end-to-end content cannot carry packet flags.");
        return (type, sequence, encoded[13], size);
    }

    internal static byte[] Encrypt(byte[] key, long sequence, byte[] plaintext, byte[] associatedData)
    {
        var nonce = SequenceNonce(sequence);
        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[TagBytes];
        using var aes = new AesGcm(key, TagBytes);
        aes.Encrypt(nonce, plaintext, ciphertext, tag, associatedData);
        return Combine(ciphertext, tag);
    }

    internal static byte[] Decrypt(byte[] key, long sequence, byte[] encoded, byte[] associatedData)
    {
        if (encoded.Length < TagBytes) throw new InvalidDataException("Truncated AES-GCM record.");
        var plaintext = new byte[encoded.Length - TagBytes];
        using var aes = new AesGcm(key, TagBytes);
        aes.Decrypt(
            SequenceNonce(sequence),
            encoded.AsSpan(0, plaintext.Length),
            encoded.AsSpan(plaintext.Length, TagBytes),
            plaintext,
            associatedData);
        return plaintext;
    }

    private static byte[] SequenceNonce(long sequence)
    {
        if (sequence < 0) throw new InvalidDataException("Negative sequence number.");
        var nonce = new byte[NonceBytes];
        BinaryPrimitives.WriteInt64BigEndian(nonce.AsSpan(NonceBytes - 8), sequence);
        return nonce;
    }

    private static byte[] HkdfSha256(byte[] input, byte[] salt, byte[] info, int length)
    {
        using var extractMac = new HMACSHA256(salt);
        var extract = extractMac.ComputeHash(input);
        var output = new byte[length];
        var previous = Array.Empty<byte>();
        var position = 0;
        byte counter = 1;
        while (position < length)
        {
            using var expandMac = new HMACSHA256(extract);
            var block = expandMac.ComputeHash(Combine(previous, info, [counter]));
            var count = Math.Min(block.Length, length - position);
            block.AsSpan(0, count).CopyTo(output.AsSpan(position));
            position += count;
            CryptographicOperations.ZeroMemory(previous);
            previous = block;
            counter++;
        }
        CryptographicOperations.ZeroMemory(extract);
        CryptographicOperations.ZeroMemory(previous);
        return output;
    }

    private static byte[] Combine(params byte[][] arrays)
    {
        var length = arrays.Sum(array => array.Length);
        var result = new byte[length];
        var offset = 0;
        foreach (var array in arrays)
        {
            array.CopyTo(result, offset);
            offset += array.Length;
        }
        return result;
    }

    private static void WriteUInt16(Stream output, ushort value)
    {
        Span<byte> bytes = stackalloc byte[2];
        BinaryPrimitives.WriteUInt16BigEndian(bytes, value);
        output.Write(bytes);
    }

    private sealed record HandshakeHello(
        byte Role,
        byte[] NodeId,
        byte[] IdentityPublicKey,
        byte[] EphemeralPublicKey,
        byte[] Nonce,
        VideoNodeCapabilities Capabilities,
        byte[] Signature);

    private sealed class PendingHello : IDisposable
    {
        internal PendingHello(HandshakeHello hello, ECDiffieHellman ephemeral, bool endToEnd)
        {
            Hello = hello;
            Ephemeral = ephemeral;
            EndToEnd = endToEnd;
        }

        internal HandshakeHello Hello { get; }
        internal ECDiffieHellman Ephemeral { get; }
        internal bool EndToEnd { get; }
        public void Dispose() => Ephemeral.Dispose();
    }

    private sealed record SessionKeys(byte[] Send, byte[] Receive);
}
