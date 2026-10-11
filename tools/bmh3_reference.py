#!/usr/bin/env python3
"""BMH3 identity-hiding handshake: executable reference and test-vector generator.

Normative companion of docs/PROTOCOL.md "Experimental BMH3 identity-hiding link handshake". It is host
tooling, not app code. It implements the wire format, the key schedule, both reveal orders, the contact
tag and the local checks exactly as the document specifies, and it generates the byte-exact vectors the
Android JVM tests (`Bmh3Vectors.kt`) replay.

    python -B tools/bmh3_reference.py                 run the scenarios and print a summary
    python -B tools/bmh3_reference.py --json FILE     write the vectors as JSON
    python -B tools/bmh3_reference.py --kotlin FILE   write the Kotlin fixture used by the JVM tests

Needs the third-party `cryptography` package (46.x tested). Everything is deterministic: fixed private
scalars, fixed nonces and RFC 6979 deterministic ECDSA, so every byte reproduces. A phone signs with a
random k, so the app tests inject the vector's ephemeral keys, nonces and signatures (or check the
receiving side only).
"""
from __future__ import annotations

import hashlib
import hmac
import json
import struct
import sys
from dataclasses import dataclass, field

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"BMH3"
VERSION = 1
SUITE_P256 = 1                     # offer byte: P-256 ECDH, HKDF-SHA-256, AES-256-GCM, ECDSA-P256-SHA256
PURPOSE_LINK, PURPOSE_ENDPOINT = 1, 2
PURPOSE_NAME = {PURPOSE_LINK: b"Link", PURPOSE_ENDPOINT: b"EndToEnd"}
ROOT = b"BluetoothVideoMesh/BMH3/"
TAG_INFO = b"BluetoothVideoMesh/BMH3/ContactTag/1"

F1_MAY_REVEAL_FIRST = 0x01         # msg1 flag: the initiator would accept I_FIRST (a hint only)
F2_R_FIRST = 0x01                  # msg2 flag: 1 = the responder's block follows (SIGMA-I), 0 = I_FIRST (SIGMA-R)

HEADER_BYTES = 8
EPH_BYTES = 65
NONCE_BYTES = 32
TAG_BYTES = 16
BINDING_BYTES = 11                 # selected advert bytes (6) | role (1) | wall epoch (4, version 2 adverts only)
SPKI_BYTES = 91
CAPS_BYTES = 7
EXT_AREA_BYTES = 112               # every block carries exactly this many extension bytes (PAD fills)
SIG_BYTES = 64
MAC_BYTES = 32
MSG1_BYTES = HEADER_BYTES + 1 + EPH_BYTES + NONCE_BYTES + TAG_BYTES          # 122
MSG2_CLEAR_BYTES = HEADER_BYTES + EPH_BYTES + NONCE_BYTES                    # 105
BLOCK_BYTES = 2 + SPKI_BYTES + CAPS_BYTES + 2 + EXT_AREA_BYTES + SIG_BYTES + MAC_BYTES   # 294
P256_SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
P256_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
P256_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551

EXT_PAD, EXT_OPENING, EXT_INTENT, EXT_BUILD_TAG = 0x00, 0x01, 0x02, 0x03
EXT_PSEUDONYM = 0x04               # empty value: the key of this block is a throwaway (Phase 2, pseudonymous strangers)
CRITICAL_BIT = 0x80
ZERO_NONCE = bytes(12)
INTENT_CALL = 1


class HandshakeError(Exception):
    pass


# ------------------------------------------------------------------------------------------ primitives

def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac.new(salt, ikm, hashlib.sha256).digest()


def hkdf_expand(prk: bytes, info: bytes, length: int = 32) -> bytes:
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def domain(purpose: int) -> bytes:
    return ROOT + PURPOSE_NAME[purpose]


def expand_label(purpose: int, secret: bytes, label: str) -> bytes:
    return hkdf_expand(secret, domain(purpose) + b"/" + label.encode("ascii"), 32)


def th(purpose: int, transcript: bytes) -> bytes:
    return sha256(domain(purpose) + b"\x00" + transcript)


def sig_context(purpose: int, role: str, transcript_hash: bytes) -> bytes:
    return domain(purpose) + b"/" + role.encode("ascii") + b" signature\x00" + transcript_hash


def header(purpose: int, msg: int, flags: int) -> bytes:
    return MAGIC + bytes([VERSION, purpose, msg, flags])


def parse_header(data: bytes, purpose: int, msg: int) -> int:
    if len(data) < HEADER_BYTES or data[:4] != MAGIC:
        raise HandshakeError("bad magic")
    if data[4] != VERSION:
        raise HandshakeError("unsupported version")
    if data[5] != purpose:
        raise HandshakeError("wrong purpose (domain)")
    if data[6] != msg:
        raise HandshakeError(f"expected message {msg}, got {data[6]}")
    return data[7]


def scalar_key(label: str) -> ec.EllipticCurvePrivateKey:
    value = int.from_bytes(sha256(b"BMH3 test vector scalar/" + label.encode()), "big") % (P256_N - 1) + 1
    return ec.derive_private_key(value, ec.SECP256R1())


def eph_bytes(key: ec.EllipticCurvePrivateKey) -> bytes:
    return key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def spki_bytes(key: ec.EllipticCurvePrivateKey) -> bytes:
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    assert len(der) == SPKI_BYTES and der.startswith(P256_SPKI_PREFIX)
    return der


def node_id(spki: bytes) -> bytes:
    return sha256(spki)[:16]


def load_eph(raw: bytes) -> ec.EllipticCurvePublicKey:
    """Explicit, provider-independent validation: 65 bytes, 0x04, both coordinates < p, on the curve."""
    if len(raw) != EPH_BYTES or raw[0] != 0x04:
        raise HandshakeError("ephemeral must be a 65-byte uncompressed SEC1 point")
    x, y = int.from_bytes(raw[1:33], "big"), int.from_bytes(raw[33:], "big")
    if x >= P256_P or y >= P256_P:
        raise HandshakeError("ephemeral coordinate out of range")
    try:  # cofactor 1, so on-curve is sufficient
        return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)
    except ValueError as error:
        raise HandshakeError("ephemeral not on curve") from error


def load_identity(spki: bytes) -> ec.EllipticCurvePublicKey:
    if len(spki) != SPKI_BYTES or not spki.startswith(P256_SPKI_PREFIX):
        raise HandshakeError("identity must be the canonical 91-byte P-256 SPKI")
    try:
        return serialization.load_der_public_key(spki)
    except ValueError as error:
        raise HandshakeError("identity key invalid") from error


def sign_raw(key: ec.EllipticCurvePrivateKey, message: bytes) -> bytes:
    der = key.sign(message, ec.ECDSA(hashes.SHA256(), deterministic_signing=True))
    r, s = decode_dss_signature(der)
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def verify_raw(pub: ec.EllipticCurvePublicKey, message: bytes, raw: bytes) -> None:
    r, s = int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")
    if not (0 < r < P256_N and 0 < s < P256_N):
        raise HandshakeError("signature scalar out of range")
    try:
        pub.verify(encode_dss_signature(r, s), message, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as error:
        raise HandshakeError("signature verification failed") from error


def seal(key: bytes, aad: bytes, plaintext: bytes) -> bytes:
    return AESGCM(key).encrypt(ZERO_NONCE, plaintext, aad)


def unseal(key: bytes, aad: bytes, ciphertext: bytes) -> bytes:
    try:
        return AESGCM(key).decrypt(ZERO_NONCE, ciphertext, aad)
    except InvalidTag as error:
        raise HandshakeError("handshake AEAD failed") from error


# ---------------------------------------------------------------------------------------------- extensions

def encode_extensions(exts: list[tuple[int, bytes]]) -> bytes:
    out, last = b"", -1
    for ext_type, value in exts:
        if ext_type <= last:
            raise ValueError("extensions must be unique and ascending")
        last = ext_type
        out += bytes([ext_type]) + struct.pack(">H", len(value)) + value
    return out


def ext_area(exts: list[tuple[int, bytes]]) -> bytes:
    """The 112-byte extension area: the given extensions (ascending, no PAD) then a PAD of zeros."""
    body = encode_extensions(exts)
    spare = EXT_AREA_BYTES - len(body)
    if spare < 0 or 0 < spare < 3:
        raise ValueError("extensions do not fit the 112-byte area")
    return encode_extensions([(EXT_PAD, bytes(spare - 3))] + exts) if spare else body


def parse_extensions(data: bytes) -> dict[int, bytes]:
    if len(data) != EXT_AREA_BYTES:
        raise HandshakeError("extension area must be exactly 112 bytes")
    result, pos, last = {}, 0, -1
    while pos < len(data):
        if pos + 3 > len(data):
            raise HandshakeError("truncated extension")
        ext_type = data[pos]
        (length,) = struct.unpack(">H", data[pos + 1:pos + 3])
        value = data[pos + 3:pos + 3 + length]
        if len(value) != length:
            raise HandshakeError("truncated extension value")
        if ext_type <= last:
            raise HandshakeError("extensions not unique/ascending")
        last = ext_type
        if ext_type == EXT_PAD and any(value):
            raise HandshakeError("non-zero padding")
        if ext_type == EXT_BUILD_TAG and len(value) != 2:
            raise HandshakeError("build tag malformed")
        if ext_type == EXT_PSEUDONYM and value:
            raise HandshakeError("pseudonym marker malformed")
        if ext_type not in (EXT_PAD, EXT_OPENING, EXT_INTENT, EXT_BUILD_TAG, EXT_PSEUDONYM) and ext_type & CRITICAL_BIT:
            raise HandshakeError("unknown critical extension")
        result[ext_type] = value
        pos += 3 + length
    return result


def opening_value(entries: list[bytes]) -> bytes:
    """OPENING value = count (1, 0..2) | count x 43-byte entry (the BDO1 body after its version byte)."""
    if len(entries) > 2 or any(len(e) != 43 for e in entries):
        raise ValueError("opening entries")
    return bytes([len(entries)]) + b"".join(entries)


def check_placement(ext: dict[int, bytes], *, responder_block: bool, msg: int, order: str, purpose: int) -> None:
    """Known extensions in the wrong place fail the handshake (v1)."""
    if EXT_BUILD_TAG in ext and purpose != PURPOSE_LINK:
        raise HandshakeError("BUILD_TAG outside the link handshake")
    if EXT_OPENING in ext:
        value = ext[EXT_OPENING]
        if not responder_block:
            raise HandshakeError("OPENING outside a responder block")
        if not value or value[0] > 2 or len(value) != 1 + 43 * value[0]:
            raise HandshakeError("OPENING malformed")
    if EXT_OPENING in ext and EXT_PSEUDONYM in ext:
        raise HandshakeError("OPENING in a pseudonymous block")
    if EXT_INTENT in ext:
        if responder_block or msg != 3 or order != "I_FIRST":
            raise HandshakeError("INTENT outside msg3 of an I_FIRST handshake")
        if len(ext[EXT_INTENT]) != 1:
            raise HandshakeError("INTENT malformed")


# ---------------------------------------------------------------------------------------------- contact tag

def contact_tag_key(lease_key: bytes) -> bytes:
    """K_tag = HKDF-Expand(L_d, "BluetoothVideoMesh/BMH3/ContactTag/1", 32). L_d is the responder's day lease key."""
    return hkdf_expand(lease_key, TAG_INFO, 32)


def advert_binding(selected6: bytes, role: int, wall_epoch: int | None) -> bytes:
    """What the tag binds to (BR3): the advert the initiator selected, so a recorded tag is useless in another epoch.

    A version 5 RDI already changes every epoch, so its epoch field is 0; a version 2 (fixed prefix) advert carries
    the wall epoch of the initiator's clock instead (the responder tries epochs -1, 0, +1).
    """
    assert len(selected6) == 6
    return selected6 + bytes([role]) + struct.pack(">I", wall_epoch or 0)


def contact_tag(purpose: int, tag_key: bytes, msg1_with_zero_tag: bytes, binding: bytes) -> bytes:
    assert len(binding) == BINDING_BYTES
    return hmac.new(tag_key, th(purpose, msg1_with_zero_tag) + binding, hashlib.sha256).digest()[:TAG_BYTES]


# ---------------------------------------------------------------------------------------------- parties

@dataclass
class Party:
    role: str                      # "Initiator" or "Responder"
    purpose: int
    identity: ec.EllipticCurvePrivateKey
    ephemeral: ec.EllipticCurvePrivateKey
    nonce: bytes
    caps: bytes
    extensions: bytes = b""        # a 112-byte area; "" means "no extensions" (all PAD)
    transcript: bytes = b""
    hs: bytes = b""
    keys: dict = field(default_factory=dict)
    peer_spki: bytes = b""
    peer_caps: bytes = b""
    peer_ext: dict = field(default_factory=dict)
    trace: dict = field(default_factory=dict)
    sig_role_override: str | None = None   # negative tests only
    corrupt_mac: bool = False              # negative tests only

    def __post_init__(self):
        if not self.extensions:
            self.extensions = ext_area([])

    def derive_handshake(self, peer_eph: ec.EllipticCurvePublicKey) -> None:
        dh = self.ephemeral.exchange(ec.ECDH(), peer_eph)
        th2 = th(self.purpose, self.transcript)
        self.hs = hkdf_extract(th2, dh)
        p = self.purpose
        self.keys = {
            "hs_i2r": expand_label(p, self.hs, "hs i2r"),
            "hs_r2i": expand_label(p, self.hs, "hs r2i"),
            "finished_i": expand_label(p, self.hs, "finished i"),
            "finished_r": expand_label(p, self.hs, "finished r"),
            "derived": expand_label(p, self.hs, "derived"),
        }
        self.trace.update(dh=dh.hex(), th2=th2.hex(), hs=self.hs.hex(), **{k: v.hex() for k, v in self.keys.items()})

    def identity_block(self, transcript_prefix: bytes) -> bytes:
        """transcript_prefix = transcript so far plus the carrying message's cleartext part (what TH covers)."""
        prefix = struct.pack(">H", SPKI_BYTES) + spki_bytes(self.identity) + self.caps
        prefix += struct.pack(">H", len(self.extensions)) + self.extensions
        th_sig = th(self.purpose, transcript_prefix + prefix)
        signature = sign_raw(self.identity, sig_context(self.purpose, self.sig_role_override or self.role, th_sig))
        fk = self.keys["finished_i" if self.role == "Initiator" else "finished_r"]
        th_mac = th(self.purpose, transcript_prefix + prefix + signature)
        mac = hmac.new(fk, th_mac, hashlib.sha256).digest()
        if self.corrupt_mac:
            mac = bytes([mac[0] ^ 1]) + mac[1:]
        tag = self.role[0]
        self.trace.update({f"th_sig_{tag}": th_sig.hex(), f"sig_{tag}": signature.hex(),
                           f"th_mac_{tag}": th_mac.hex(), f"mac_{tag}": mac.hex()})
        block = prefix + signature + mac
        assert len(block) == BLOCK_BYTES
        return block

    def open_identity_block(self, transcript_prefix: bytes, block: bytes, *, msg: int, order: str) -> None:
        peer_role = "Responder" if self.role == "Initiator" else "Initiator"
        if len(block) != BLOCK_BYTES:
            raise HandshakeError("identity block length")
        (spki_len,) = struct.unpack(">H", block[:2])
        if spki_len != SPKI_BYTES:
            raise HandshakeError("identity length")
        pos = 2
        spki = block[pos:pos + SPKI_BYTES]; pos += SPKI_BYTES
        caps = block[pos:pos + CAPS_BYTES]; pos += CAPS_BYTES
        if caps[0] != 1:
            raise HandshakeError("capabilities")
        (ext_len,) = struct.unpack(">H", block[pos:pos + 2]); pos += 2
        if ext_len != EXT_AREA_BYTES:
            raise HandshakeError("extension area length")
        ext = block[pos:pos + ext_len]; pos += ext_len
        prefix = block[:pos]
        signature = block[pos:pos + SIG_BYTES]; pos += SIG_BYTES
        mac = block[pos:pos + MAC_BYTES]
        pub = load_identity(spki)
        verify_raw(pub, sig_context(self.purpose, peer_role, th(self.purpose, transcript_prefix + prefix)), signature)
        fk = self.keys["finished_r" if self.role == "Initiator" else "finished_i"]
        expected = hmac.new(fk, th(self.purpose, transcript_prefix + prefix + signature), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, mac):
            raise HandshakeError("finished MAC mismatch")
        parsed = parse_extensions(ext)
        check_placement(parsed, responder_block=(peer_role == "Responder"), msg=msg, order=order, purpose=self.purpose)
        if spki == spki_bytes(self.identity):
            raise HandshakeError("peer identity equals own identity (self-connection)")
        self.peer_spki, self.peer_caps, self.peer_ext = spki, caps, parsed

    def finish(self) -> None:
        th_final = th(self.purpose, self.transcript)
        ms = hkdf_extract(th_final, self.keys["derived"])
        p = self.purpose
        self.keys.update(app_i2r=expand_label(p, ms, "app i2r"), app_r2i=expand_label(p, ms, "app r2i"),
                         exporter=expand_label(p, ms, "exporter"))
        self.trace.update(th_final=th_final.hex(), ms=ms.hex(), app_i2r=self.keys["app_i2r"].hex(),
                          app_r2i=self.keys["app_r2i"].hex(), exporter=self.keys["exporter"].hex(),
                          peer_node_id=node_id(self.peer_spki).hex())


class Initiator(Party):
    def __init__(self, *, may_reveal_first: bool = False, tag: tuple[bytes, bytes] | None = None,
                 random_tag: bytes = bytes(16), check_responder=None, **kw):
        super().__init__(role="Initiator", **kw)
        self.may_reveal_first = may_reveal_first   # SR1: false unless an explicit user confirmation exists
        self.tag = tag                             # (K_tag, binding) of the selected contact, or None
        self.tag_key = tag[0] if tag else None
        self.random_tag = random_tag               # 16 random bytes sent when there is no tag key
        self.check_responder = check_responder     # runs before this side reveals anything
        self.order = None

    def msg1(self) -> bytes:
        flags = F1_MAY_REVEAL_FIRST if self.may_reveal_first else 0
        body = header(self.purpose, 1, flags) + bytes([SUITE_P256]) + eph_bytes(self.ephemeral) + self.nonce
        tag = self.random_tag if self.tag is None else contact_tag(self.purpose, self.tag[0], body + bytes(TAG_BYTES), self.tag[1])
        m1 = body + tag
        assert len(m1) == MSG1_BYTES
        self.transcript = m1
        return m1

    def on_msg2(self, m2: bytes) -> bytes | None:
        flags = parse_header(m2, self.purpose, 2)
        if flags & ~F2_R_FIRST:
            raise HandshakeError("reserved msg2 flags")
        if len(m2) < MSG2_CLEAR_BYTES:
            raise HandshakeError("short msg2")
        clear, rest = m2[:MSG2_CLEAR_BYTES], m2[MSG2_CLEAR_BYTES:]
        peer_eph_raw = clear[HEADER_BYTES:HEADER_BYTES + EPH_BYTES]
        peer_eph = load_eph(peer_eph_raw)
        if peer_eph_raw == eph_bytes(self.ephemeral):
            raise HandshakeError("reflected ephemeral key")
        self.transcript += clear
        self.derive_handshake(peer_eph)
        if flags & F2_R_FIRST:
            self.order = "R_FIRST"
            block = unseal(self.keys["hs_r2i"], clear, rest)
            self.open_identity_block(self.transcript, block, msg=2, order="R_FIRST")
            self.transcript += block
            if self.check_responder:                      # discovery binding, verified contacts, admission
                self.check_responder(self.peer_spki, self.peer_ext)
            return self._msg3()
        self.order = "I_FIRST"
        if rest:
            raise HandshakeError("I_FIRST msg2 must not carry ciphertext")
        if self.tag_key is not None:
            raise HandshakeError("tag sent: a contact answers R_FIRST; closing without revealing")
        if not self.may_reveal_first:
            raise HandshakeError("I_FIRST refused by local policy (SR1)")
        return self._msg3()

    def _msg3(self) -> bytes:
        h3 = header(self.purpose, 3, 0)
        block = self.identity_block(self.transcript + h3)
        m3 = h3 + seal(self.keys["hs_i2r"], h3, block)
        self.transcript += h3 + block
        if self.order == "R_FIRST":
            self.finish()
        return m3

    def on_msg4(self, m4: bytes) -> None:
        if self.order != "I_FIRST":
            raise HandshakeError("unexpected msg4")
        flags = parse_header(m4, self.purpose, 4)
        if flags:
            raise HandshakeError("reserved msg4 flags")
        h4 = m4[:HEADER_BYTES]
        block = unseal(self.keys["hs_r2i"], h4, m4[HEADER_BYTES:])
        self.open_identity_block(self.transcript + h4, block, msg=4, order="I_FIRST")
        self.transcript += h4 + block
        if self.check_responder:                          # I_FIRST: after the reveal, but it still aborts the session
            self.check_responder(self.peer_spki, self.peer_ext)
        self.finish()


class Responder(Party):
    def __init__(self, *, mode: str = "EVERYONE", tag_checks: list[tuple[bytes, bytes]] | None = None,
                 admit_initiator=None, **kw):
        super().__init__(role="Responder", **kw)
        self.mode = mode                      # "EVERYONE" or "CONTACTS_ONLY"
        self.tag_checks = tag_checks or []    # (K_tag, binding) candidates: own lease keys x currently advertised IDs
        self.admit_initiator = admit_initiator
        self.order = None
        self.tag_matched = False

    def on_msg1(self, m1: bytes) -> bytes:
        flags = parse_header(m1, self.purpose, 1)
        if flags & ~F1_MAY_REVEAL_FIRST:
            raise HandshakeError("reserved msg1 flags")
        if len(m1) != MSG1_BYTES:
            raise HandshakeError("msg1 length")
        if m1[HEADER_BYTES] != SUITE_P256:
            raise HandshakeError("unsupported suite")
        peer_eph_raw = m1[HEADER_BYTES + 1:HEADER_BYTES + 1 + EPH_BYTES]
        peer_eph = load_eph(peer_eph_raw)
        if peer_eph_raw == eph_bytes(self.ephemeral):
            raise HandshakeError("reflected ephemeral key")
        body, tag = m1[:-TAG_BYTES], m1[-TAG_BYTES:]
        self.tag_matched = any(
            hmac.compare_digest(contact_tag(self.purpose, key, body + bytes(TAG_BYTES), binding), tag)
            for key, binding in self.tag_checks)
        self.order = "R_FIRST" if (self.mode == "EVERYONE" or self.tag_matched) else "I_FIRST"
        if self.order == "I_FIRST" and not flags & F1_MAY_REVEAL_FIRST:
            raise HandshakeError("order conflict: closing without replying")
        self.transcript = m1
        clear = header(self.purpose, 2, F2_R_FIRST if self.order == "R_FIRST" else 0)
        clear += eph_bytes(self.ephemeral) + self.nonce
        self.transcript += clear
        self.derive_handshake(peer_eph)
        if self.order == "I_FIRST":
            return clear
        block = self.identity_block(self.transcript)
        self.transcript += block
        return clear + seal(self.keys["hs_r2i"], clear, block)

    def on_msg3(self, m3: bytes) -> bytes | None:
        flags = parse_header(m3, self.purpose, 3)
        if flags:
            raise HandshakeError("reserved msg3 flags")
        h3 = m3[:HEADER_BYTES]
        block = unseal(self.keys["hs_i2r"], h3, m3[HEADER_BYTES:])
        self.open_identity_block(self.transcript + h3, block, msg=3, order=self.order)
        self.transcript += h3 + block
        if self.order == "R_FIRST":
            if self.admit_initiator and not self.admit_initiator(self.peer_spki, self.peer_ext, self.tag_matched):
                raise HandshakeError("initiator refused by local policy")
            self.finish()
            return None
        if self.admit_initiator is None or not self.admit_initiator(self.peer_spki, self.peer_ext, self.tag_matched):
            raise HandshakeError("I_FIRST: stranger gets a silent close, no identity revealed")
        h4 = header(self.purpose, 4, 0)
        block4 = self.identity_block(self.transcript + h4)
        self.transcript += h4 + block4
        self.finish()
        return h4 + seal(self.keys["hs_r2i"], h4, block4)


# ---------------------------------------------------------------------------------------------- identity reveal

REVEAL_DOMAIN = b"BluetoothVideoMesh/BMH3/Reveal/1"
ROLE_CALLER, ROLE_CALLEE = 1, 2
BDX1_KIND_REVEAL = 4


REVEAL_KEY_INFO = b"BluetoothVideoMesh/BMH3/Reveal/1 key"


def reveal_signing_input(role: int, exporter: bytes, own_pseudonym_spki: bytes, peer_pseudonym_spki: bytes,
                         real_spki: bytes, entries: list[bytes], prior: bytes) -> bytes:
    """What the real key signs: D "/" Role " signature" 0x00 TH_R. TH_R covers the role, K = HKDF-Expand(exporter, "... key"),
    the signer's own and the peer's pseudonym hashes, the real key, `prior` (empty in the callee's reveal, the hash of the
    callee's record in the caller's) and the opening body (PROTOCOL "REVEAL")."""
    body = bytes([len(entries)]) + b"".join(entries)
    key = hkdf_expand(exporter, REVEAL_KEY_INFO)
    th_r = sha256(REVEAL_DOMAIN + b"\x00" + bytes([role]) + key + sha256(own_pseudonym_spki)
                  + sha256(peer_pseudonym_spki) + real_spki + prior + body)
    name = b"Caller" if role == ROLE_CALLER else b"Callee"
    return REVEAL_DOMAIN + b"/" + name + b" signature" + b"\x00" + th_r


def build_reveal(role: int, real_key: ec.EllipticCurvePrivateKey, exporter: bytes, own_pseudonym_spki: bytes,
                 peer_pseudonym_spki: bytes, entries: list[bytes], prior: bytes) -> bytes:
    spki = spki_bytes(real_key)
    sig = sign_raw(real_key, reveal_signing_input(role, exporter, own_pseudonym_spki, peer_pseudonym_spki, spki, entries, prior))
    return (b"BDX1" + bytes([BDX1_KIND_REVEAL, role]) + struct.pack(">H", SPKI_BYTES) + spki
            + bytes([len(entries)]) + b"".join(entries) + sig)


def parse_reveal(record: bytes) -> tuple[int, bytes, list[bytes], bytes]:
    if len(record) < 100 + SIG_BYTES or record[:4] != b"BDX1" or record[4] != BDX1_KIND_REVEAL:
        raise HandshakeError("not a reveal record")
    role = record[5]
    (spki_len,) = struct.unpack(">H", record[6:8])
    if role not in (ROLE_CALLER, ROLE_CALLEE) or spki_len != SPKI_BYTES:
        raise HandshakeError("reveal role or identity length")
    spki, count = record[8:99], record[99]
    if count > 2 or len(record) != 100 + 43 * count + SIG_BYTES:
        raise HandshakeError("reveal length")
    if role == ROLE_CALLER and count:
        raise HandshakeError("only the callee opens a rotating ID")
    entries = [record[100 + 43 * i:100 + 43 * (i + 1)] for i in range(count)]
    return role, spki, entries, record[-SIG_BYTES:]


def verify_reveal(record: bytes, expected_role: int, exporter: bytes, peer_pseudonym_spki: bytes,
                  own_pseudonym_spki: bytes, own_spkis: list[bytes], prior: bytes) -> bytes:
    role, spki, entries, sig = parse_reveal(record)
    if role != expected_role:
        raise HandshakeError("reveal role")
    if spki == peer_pseudonym_spki:
        raise HandshakeError("reveal names the pseudonym")
    if spki in own_spkis:
        raise HandshakeError("reveal names this phone")
    verify_raw(load_identity(spki), reveal_signing_input(role, exporter, peer_pseudonym_spki, own_pseudonym_spki, spki, entries, prior), sig)
    return spki


def reveal_vectors(ini: Initiator, res: Responder) -> dict:
    """Both reveals of a pseudonymous call session: the real keys are identity/I and identity/R."""
    exporter = ini.keys["exporter"]
    real_i, real_r = scalar_key("identity/I"), scalar_key("identity/R")
    pi, pr = spki_bytes(ini.identity), spki_bytes(res.identity)
    callee = build_reveal(ROLE_CALLEE, real_r, exporter, pr, pi, [OPENING_ENTRY], b"")
    prior = sha256(callee)                                  # the caller binds the callee record it verified
    caller = build_reveal(ROLE_CALLER, real_i, exporter, pi, pr, [], prior)
    # The caller verifies the callee's and the callee the caller's, each against the peer's pseudonym.
    assert verify_reveal(callee, ROLE_CALLEE, exporter, pr, pi, [pi], b"") == spki_bytes(real_r)
    assert verify_reveal(caller, ROLE_CALLER, exporter, pi, pr, [pr], prior) == spki_bytes(real_i)
    return {
        "real_I_scalar": hex(real_i.private_numbers().private_value), "real_R_scalar": hex(real_r.private_numbers().private_value),
        "real_I_spki": spki_bytes(real_i).hex(), "real_R_spki": spki_bytes(real_r).hex(),
        "node_id_real_I": node_id(spki_bytes(real_i)).hex(), "node_id_real_R": node_id(spki_bytes(real_r)).hex(),
        "exporter": exporter.hex(),
        "caller": caller.hex(), "callee": callee.hex(),
        "caller_input": reveal_signing_input(ROLE_CALLER, exporter, pi, pr, spki_bytes(real_i), [], prior).hex(),
        "callee_input": reveal_signing_input(ROLE_CALLEE, exporter, pr, pi, spki_bytes(real_r), [OPENING_ENTRY], b"").hex(),
        "callee_record_hash": prior.hex(),
        "caller_sig": caller[-SIG_BYTES:].hex(), "callee_sig": callee[-SIG_BYTES:].hex(),
    }


# ---------------------------------------------------------------------------------------------- vectors

LINK_CAPS_I = bytes.fromhex("01" "02" "03" "03" "05dc" "00")   # v1, LE CoC, VP9+AV1 enc/dec, 1500 kbps, 0 children
LINK_CAPS_R = bytes.fromhex("01" "0a" "03" "03" "05dc" "01")   # LE CoC + bit 3 (BDO1), maxChildren 1
E2E_CAPS_I = bytes.fromhex("01" "02" "01" "83" "0190" "00")    # VP9 encode, VP9+AV1 decode + prefer-VP9 bit 7, 400 kbps
E2E_CAPS_R = bytes.fromhex("01" "0a" "03" "03" "0190" "00")
LEASE_KEY = bytes(range(0x40, 0x60))                            # the responder's day lease key L_d in the vectors
OPENING_ENTRY = bytes.fromhex("44e69c0a7a7d001e600000d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167")
ZERO_RANDOM_TAG = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
BUILD_TAG_I = bytes.fromhex("0006")                                   # release 6, not signed with the release key
BUILD_TAG_R = bytes.fromhex("8007")                                   # release 7, signed with the release key
VECTOR_BINDING = advert_binding(OPENING_ENTRY[:6], 0, None)          # the RDI of OPENING_ENTRY, call role, version 5


def make_pair(purpose: int, tag: str, *, mode="EVERYONE", init_tag=None, may_reveal_first=False,
              resp_tag_checks=None, r_ext=b"", i_ext=b"", admit=None, check_responder=None,
              identity_labels=("identity/I", "identity/R")):
    caps_i, caps_r = (LINK_CAPS_I, LINK_CAPS_R) if purpose == PURPOSE_LINK else (E2E_CAPS_I, E2E_CAPS_R)
    ini = Initiator(may_reveal_first=may_reveal_first, tag=init_tag, random_tag=ZERO_RANDOM_TAG,
                    check_responder=check_responder, purpose=purpose,
                    identity=scalar_key(identity_labels[0]), ephemeral=scalar_key(f"{tag}/ephemeral/I"),
                    nonce=bytes(range(32)), caps=caps_i, extensions=i_ext)
    res = Responder(mode=mode, tag_checks=resp_tag_checks, admit_initiator=admit, purpose=purpose,
                    identity=scalar_key(identity_labels[1]), ephemeral=scalar_key(f"{tag}/ephemeral/R"),
                    nonce=bytes(range(32, 64)), caps=caps_r, extensions=r_ext)
    return ini, res


def run(ini: Initiator, res: Responder) -> dict:
    msgs = {"m1": ini.msg1()}
    msgs["m2"] = res.on_msg1(msgs["m1"])
    msgs["m3"] = ini.on_msg2(msgs["m2"])
    m4 = res.on_msg3(msgs["m3"])
    if m4 is not None:
        msgs["m4"] = m4
        ini.on_msg4(m4)
    assert ini.keys["app_i2r"] == res.keys["app_i2r"] and ini.keys["app_r2i"] == res.keys["app_r2i"]
    assert ini.peer_spki == spki_bytes(res.identity) and res.peer_spki == spki_bytes(ini.identity)
    return msgs


def bms1_first_record(key: bytes, record_type: int, payload: bytes) -> bytes:
    """The first BMS1 record (sequence 0) a side sends under its session key (PROTOCOL BMS1)."""
    ct_len = len(payload) + 16
    hdr = b"BMS1" + bytes([record_type]) + struct.pack(">Q", 0) + struct.pack(">I", ct_len)
    nonce = bytes(4) + struct.pack(">Q", 0)
    return hdr + AESGCM(key).encrypt(nonce, payload, hdr)


def expect_failure(label: str, fn) -> dict:
    try:
        fn()
    except HandshakeError as error:
        return {"case": label, "result": "rejected", "reason": str(error)}
    raise AssertionError(f"negative case did not fail: {label}")


def negative_cases() -> list[dict]:
    out = []
    lease = contact_tag_key(LEASE_KEY)

    def tamper_m1_flag():
        ini, res = make_pair(PURPOSE_LINK, "neg1")
        m1 = bytearray(ini.msg1()); m1[7] ^= F1_MAY_REVEAL_FIRST
        ini.on_msg2(res.on_msg1(bytes(m1)))
    out.append(expect_failure("1 msg1 flag flipped in transit", tamper_m1_flag))

    def order_downgrade():  # SR1: an attacker answering with I_FIRST gets nothing from a default initiator
        ini, res = make_pair(PURPOSE_LINK, "neg2", mode="CONTACTS_ONLY", may_reveal_first=True)
        m2 = res.on_msg1(ini.msg1())
        ini.may_reveal_first = False
        ini.on_msg2(m2)
    out.append(expect_failure("2 I_FIRST demanded from an initiator that refuses it (no identity sent)", order_downgrade))

    def tag_then_i_first():  # SR1/SR2: a tag was sent, so only R_FIRST is acceptable
        ini, res = make_pair(PURPOSE_LINK, "neg2b", mode="CONTACTS_ONLY", init_tag=(contact_tag_key(bytes(32)), VECTOR_BINDING),
                             may_reveal_first=True, resp_tag_checks=[(lease, VECTOR_BINDING)])
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("2b tag sent but the responder answers I_FIRST (wrong lease key)", tag_then_i_first))

    def tag_replayed_in_another_epoch():  # BR3: the tag is bound to the advert; a new epoch has a new RDI
        old_binding = advert_binding(bytes(range(6)), 0, None)
        ini, res = make_pair(PURPOSE_LINK, "neg2c", mode="CONTACTS_ONLY", init_tag=(lease, old_binding),
                             may_reveal_first=True, resp_tag_checks=[(lease, VECTOR_BINDING)])
        res.on_msg1(ini.msg1())
        assert not res.tag_matched
        raise HandshakeError("tag does not match the responder's current advert: I_FIRST, nothing revealed")
    out.append(expect_failure("2c recorded tag replayed against the responder's next-epoch advert", tag_replayed_in_another_epoch))

    def cross_domain():
        ini, _ = make_pair(PURPOSE_LINK, "neg3")
        _, res = make_pair(PURPOSE_ENDPOINT, "neg3")
        res.on_msg1(ini.msg1())
    out.append(expect_failure("3 link message in the endpoint domain", cross_domain))

    def cross_domain_relabelled():
        ini, _ = make_pair(PURPOSE_LINK, "neg4")
        _, res = make_pair(PURPOSE_ENDPOINT, "neg4")
        m1 = bytearray(ini.msg1()); m1[5] = PURPOSE_ENDPOINT
        m2 = bytearray(res.on_msg1(bytes(m1))); m2[5] = PURPOSE_LINK
        ini.on_msg2(bytes(m2))
    out.append(expect_failure("4 purpose byte rewritten in both directions", cross_domain_relabelled))

    def replay_m3():
        ini_a, res_a = make_pair(PURPOSE_LINK, "neg5a", mode="CONTACTS_ONLY", may_reveal_first=True, resp_tag_checks=[(lease, VECTOR_BINDING)])
        m3_a = ini_a.on_msg2(res_a.on_msg1(ini_a.msg1()))
        ini_b, res_b = make_pair(PURPOSE_LINK, "neg5a", mode="CONTACTS_ONLY", may_reveal_first=True, resp_tag_checks=[(lease, VECTOR_BINDING)])
        res_b.ephemeral = scalar_key("neg5b/ephemeral/R")
        res_b.on_msg1(ini_b.msg1())
        res_b.on_msg3(m3_a)
    out.append(expect_failure("5 replayed msg3 into a fresh session (fresh responder ephemeral)", replay_m3))

    def tamper_ciphertext():
        ini, res = make_pair(PURPOSE_ENDPOINT, "neg6")
        m2 = bytearray(res.on_msg1(ini.msg1())); m2[-1] ^= 1
        ini.on_msg2(bytes(m2))
    out.append(expect_failure("6 msg2 ciphertext bit flip", tamper_ciphertext))

    def reflected_msg2():
        ini, res = make_pair(PURPOSE_LINK, "neg7")
        m2 = res.on_msg1(ini.msg1())
        ini.on_msg2(m2)
        res.on_msg3(header(PURPOSE_LINK, 3, 0) + m2[MSG2_CLEAR_BYTES:])
    out.append(expect_failure("7 responder block reflected as msg3", reflected_msg2))

    def reflected_resealed():  # 7b: the reflected plaintext re-sealed under the correct keys still fails (role label)
        ini, res = make_pair(PURPOSE_LINK, "neg7b")
        m2 = res.on_msg1(ini.msg1())
        ini.on_msg2(m2)
        block = unseal(res.keys["hs_r2i"], m2[:MSG2_CLEAR_BYTES], m2[MSG2_CLEAR_BYTES:])
        h3 = header(PURPOSE_LINK, 3, 0)
        res.on_msg3(h3 + seal(res.keys["hs_i2r"], h3, block))
    out.append(expect_failure("7b reflected block re-sealed under the right keys", reflected_resealed))

    def invalid_point():
        ini, res = make_pair(PURPOSE_LINK, "neg8")
        m1 = bytearray(ini.msg1()); m1[HEADER_BYTES + 1 + 64] ^= 1
        res.on_msg1(bytes(m1))
    out.append(expect_failure("8 ephemeral not on the curve", invalid_point))

    def coordinate_ge_p():  # 8b: x = p exactly, outside the field
        ini, res = make_pair(PURPOSE_LINK, "neg8b")
        m1 = bytearray(ini.msg1())
        m1[HEADER_BYTES + 2:HEADER_BYTES + 34] = P256_P.to_bytes(32, "big")
        res.on_msg1(bytes(m1))
    out.append(expect_failure("8b ephemeral coordinate >= p", coordinate_ge_p))

    def unknown_critical_ext():
        ini, res = make_pair(PURPOSE_LINK, "neg9", r_ext=encode_extensions([(EXT_PAD, bytes(105)), (0x85, b"\x01")]))
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("9 unknown critical extension", unknown_critical_ext))

    def wrong_role_signature():
        ini, res = make_pair(PURPOSE_LINK, "neg10")
        res.sig_role_override = "Initiator"
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("10 signature under the other role's label", wrong_role_signature))

    def bad_finished():
        ini, res = make_pair(PURPOSE_ENDPOINT, "neg11", mode="CONTACTS_ONLY", may_reveal_first=True,
                             admit=lambda spki, ext, matched: True)
        ini.corrupt_mac = True
        res.on_msg3(ini.on_msg2(res.on_msg1(ini.msg1())))
    out.append(expect_failure("11 finished MAC corrupted by its sender", bad_finished))

    def self_connection():  # SR6
        ini, res = make_pair(PURPOSE_LINK, "neg12")
        res.identity = ini.identity
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("12 self-connection (peer identity equals own)", self_connection))

    def responder_reuses_ephemeral():  # SR5: a replayed msg1+msg3 is accepted when (e_R, n_R) repeat
        ini, res = make_pair(PURPOSE_LINK, "neg13")
        m1 = ini.msg1()
        m3 = ini.on_msg2(res.on_msg1(m1))
        _, again = make_pair(PURPOSE_LINK, "neg13")          # the same e_R and n_R as the first responder
        again.on_msg1(m1)
        again.on_msg3(m3)                                    # does not raise: the replay is accepted
        raise AssertionError("accepted")
    try:
        responder_reuses_ephemeral()
    except AssertionError:
        out.append({"case": "13 responder reuses (e_R, n_R) for a replayed msg1+msg3", "result": "ACCEPTED",
                    "reason": "an implementation MUST draw fresh e_R and n_R for every msg1 (SR5); the replay cache is the second line"})
    except HandshakeError as error:
        out.append({"case": "13 responder reuses (e_R, n_R)", "result": "rejected", "reason": str(error)})

    def intent_in_wrong_place():  # SR7
        ini, res = make_pair(PURPOSE_LINK, "neg14", i_ext=ext_area([(EXT_INTENT, b"\x01")]))
        res.on_msg3(ini.on_msg2(res.on_msg1(ini.msg1())))
    out.append(expect_failure("14 INTENT inside an R_FIRST msg3", intent_in_wrong_place))

    def opening_from_initiator():  # SR7
        ini, res = make_pair(PURPOSE_LINK, "neg15", i_ext=ext_area([(EXT_OPENING, opening_value([OPENING_ENTRY]))]))
        res.on_msg3(ini.on_msg2(res.on_msg1(ini.msg1())))
    out.append(expect_failure("15 OPENING inside an initiator block", opening_from_initiator))

    def check_rejects_before_reveal():  # the pre-reveal check blocks msg3
        sent = []

        def refuse(spki, ext):
            raise HandshakeError("discovery binding failed")
        ini, res = make_pair(PURPOSE_LINK, "neg16", check_responder=refuse)
        sent.append(ini.on_msg2(res.on_msg1(ini.msg1())))
    out.append(expect_failure("16 pre-reveal check fails: msg3 is never produced", check_rejects_before_reveal))

    def build_tag_at_the_endpoint():  # the link-hello bit 7 is a link-only field (end-to-end bit 7 means fragments)
        ini, res = make_pair(PURPOSE_ENDPOINT, "neg18", r_ext=ext_area([(EXT_BUILD_TAG, BUILD_TAG_R)]))
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("18 BUILD_TAG inside an end-to-end block", build_tag_at_the_endpoint))

    def check_after_msg4():  # BR10a: in I_FIRST the check still runs on the responder's block and aborts the session
        def refuse(spki, ext):
            raise HandshakeError("discovery binding failed after msg4")
        ini, res = make_pair(PURPOSE_LINK, "neg17", mode="CONTACTS_ONLY", may_reveal_first=True,
                             admit=lambda spki, ext, matched: True, check_responder=refuse)
        run(ini, res)
    out.append(expect_failure("17 I_FIRST: the check on the responder's block still aborts after msg4", check_after_msg4))

    # ---- pseudonyms and the reveal (Phase 2)
    def pseudo_session(tag: str):
        pe = ext_area([(EXT_PSEUDONYM, b"")])
        ini, res = make_pair(PURPOSE_ENDPOINT, tag, identity_labels=("pseudonym/I", "pseudonym/R"), i_ext=pe, r_ext=pe)
        run(ini, res)
        return ini, res

    def reveal_in_another_session():  # a reveal recorded from one session replayed (or forwarded) into another
        ini, res = pseudo_session("neg19a")
        ini2, res2 = pseudo_session("neg19b")
        callee = build_reveal(ROLE_CALLEE, scalar_key("identity/R"), ini.keys["exporter"], spki_bytes(res.identity),
                              spki_bytes(ini.identity), [OPENING_ENTRY], b"")
        verify_reveal(callee, ROLE_CALLEE, ini2.keys["exporter"], spki_bytes(res2.identity), spki_bytes(ini2.identity),
                      [spki_bytes(ini2.identity)], b"")
    out.append(expect_failure("19 a reveal replayed into another session (exporter differs)", reveal_in_another_session))

    def reveal_with_swapped_role():  # a caller reveal presented as the callee's
        ini, res = pseudo_session("neg20")
        caller = bytearray(build_reveal(ROLE_CALLER, scalar_key("identity/I"), ini.keys["exporter"], spki_bytes(ini.identity),
                                        spki_bytes(res.identity), [], b""))
        caller[5] = ROLE_CALLEE
        verify_reveal(bytes(caller), ROLE_CALLEE, ini.keys["exporter"], spki_bytes(ini.identity), spki_bytes(res.identity),
                      [spki_bytes(res.identity)], b"")
    out.append(expect_failure("20 a caller reveal relabelled as the callee's (role is signed)", reveal_with_swapped_role))

    def reveal_names_the_pseudonym():  # the throwaway key itself offered as the real identity
        ini, res = pseudo_session("neg21")
        bad = build_reveal(ROLE_CALLEE, res.identity, ini.keys["exporter"], spki_bytes(res.identity), spki_bytes(ini.identity), [], b"")
        verify_reveal(bad, ROLE_CALLEE, ini.keys["exporter"], spki_bytes(res.identity), spki_bytes(ini.identity),
                      [spki_bytes(ini.identity)], b"")
    out.append(expect_failure("21 a reveal that names the pseudonym key", reveal_names_the_pseudonym))

    def reveal_with_tampered_opening():  # the salt is under the signature
        ini, res = pseudo_session("neg22")
        callee = bytearray(build_reveal(ROLE_CALLEE, scalar_key("identity/R"), ini.keys["exporter"], spki_bytes(res.identity),
                                        spki_bytes(ini.identity), [OPENING_ENTRY], b""))
        callee[100 + 20] ^= 1
        verify_reveal(bytes(callee), ROLE_CALLEE, ini.keys["exporter"], spki_bytes(res.identity), spki_bytes(ini.identity),
                      [spki_bytes(ini.identity)], b"")
    out.append(expect_failure("22 a reveal whose opening entry was altered", reveal_with_tampered_opening))

    def reveal_with_swapped_key():  # another key's SPKI put on a valid signature
        ini, res = pseudo_session("neg23")
        callee = bytearray(build_reveal(ROLE_CALLEE, scalar_key("identity/R"), ini.keys["exporter"], spki_bytes(res.identity),
                                        spki_bytes(ini.identity), [], b""))
        callee[8:99] = spki_bytes(scalar_key("identity/other"))
        verify_reveal(bytes(callee), ROLE_CALLEE, ini.keys["exporter"], spki_bytes(res.identity), spki_bytes(ini.identity),
                      [spki_bytes(ini.identity)], b"")
    out.append(expect_failure("23 a valid reveal signature under another key", reveal_with_swapped_key))

    def opening_in_a_pseudonymous_block():  # the salt must not leave before the user accepts
        ini, res = make_pair(PURPOSE_LINK, "neg24", identity_labels=("pseudonym/I", "pseudonym/R"),
                             r_ext=ext_area([(EXT_OPENING, opening_value([OPENING_ENTRY])), (EXT_PSEUDONYM, b"")]))
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("24 a pseudonymous block that carries an OPENING", opening_in_a_pseudonymous_block))

    def pseudonym_marker_with_value():
        ini, res = make_pair(PURPOSE_LINK, "neg25", identity_labels=("pseudonym/I", "pseudonym/R"),
                             r_ext=ext_area([(EXT_PSEUDONYM, b"\x01")]))
        ini.on_msg2(res.on_msg1(ini.msg1()))
    out.append(expect_failure("25 a PSEUDONYM marker with a value", pseudonym_marker_with_value))

    def caller_reveal_not_bound_to_the_callee_record():  # the caller reveal commits to the callee record it verified
        ini, res = pseudo_session("neg27")
        pi, pr = spki_bytes(ini.identity), spki_bytes(res.identity)
        callee = build_reveal(ROLE_CALLEE, scalar_key("identity/R"), ini.keys["exporter"], pr, pi, [OPENING_ENTRY], b"")
        caller = build_reveal(ROLE_CALLER, scalar_key("identity/I"), ini.keys["exporter"], pi, pr, [], sha256(b"another callee record"))
        verify_reveal(caller, ROLE_CALLER, ini.keys["exporter"], pi, pr, [pr], sha256(callee))
    out.append(expect_failure("27 a caller reveal that does not bind the callee's record", caller_reveal_not_bound_to_the_callee_record))

    def caller_reveal_with_entries():  # only the callee opens a rotating ID
        parse_reveal(b"BDX1" + bytes([BDX1_KIND_REVEAL, ROLE_CALLER]) + struct.pack(">H", SPKI_BYTES)
                     + spki_bytes(scalar_key("identity/I")) + bytes([1]) + OPENING_ENTRY + bytes(SIG_BYTES))
    out.append(expect_failure("26 a caller reveal that carries opening entries", caller_reveal_with_entries))
    return out


def vector_record(name: str, purpose: int, order: str, ini: Initiator, res: Responder, msgs: dict) -> dict:
    session_key_i2r, session_key_r2i = ini.keys["app_i2r"], ini.keys["app_r2i"]
    return {
        "name": name,
        "purpose": PURPOSE_NAME[purpose].decode(),
        "order": order,
        "inputs": {
            "identity_I_scalar": hex(ini.identity.private_numbers().private_value),
            "identity_R_scalar": hex(res.identity.private_numbers().private_value),
            "identity_I_spki": spki_bytes(ini.identity).hex(),
            "identity_R_spki": spki_bytes(res.identity).hex(),
            "node_id_I": node_id(spki_bytes(ini.identity)).hex(),
            "node_id_R": node_id(spki_bytes(res.identity)).hex(),
            "ephemeral_I_scalar": hex(ini.ephemeral.private_numbers().private_value),
            "ephemeral_R_scalar": hex(res.ephemeral.private_numbers().private_value),
            "nonce_I": ini.nonce.hex(), "nonce_R": res.nonce.hex(),
            "caps_I": ini.caps.hex(), "caps_R": res.caps.hex(),
            "extensions_I": ini.extensions.hex(), "extensions_R": res.extensions.hex(),
            "tag_key_I": ini.tag[0].hex() if ini.tag else "", "tag_binding_I": ini.tag[1].hex() if ini.tag else "",
            "random_tag_I": ini.random_tag.hex(),
            "tag_checks_R": [k.hex() + ":" + b.hex() for k, b in res.tag_checks], "mode_R": res.mode,
            "may_reveal_first_I": ini.may_reveal_first,
        },
        "messages": {k: v.hex() for k, v in msgs.items()},
        "message_lengths": {k: len(v) for k, v in msgs.items()},
        "bms1_first_record_i2r": bms1_first_record(session_key_i2r, 6, b"\x01").hex(),
        "bms1_first_record_r2i": bms1_first_record(session_key_r2i, 6, b"\x01").hex(),
        "initiator_trace": ini.trace,
        "responder_trace": res.trace,
    }


def build_vectors() -> dict:
    vectors = []
    # 1. Link, Everyone mode: R_FIRST (SIGMA-I), no extensions, random tag.
    ini, res = make_pair(PURPOSE_LINK, "v1")
    vectors.append(vector_record("link_r_first", PURPOSE_LINK, "R_FIRST", ini, res, run(ini, res)))
    # 2. Endpoint, Contacts-only responder and a stranger initiator who accepts I_FIRST: SIGMA-R with INTENT.
    ini, res = make_pair(PURPOSE_ENDPOINT, "v2", mode="CONTACTS_ONLY", may_reveal_first=True,
                         i_ext=ext_area([(EXT_INTENT, bytes([INTENT_CALL]))]), admit=lambda spki, ext, matched: True)
    vectors.append(vector_record("endpoint_i_first_intent", PURPOSE_ENDPOINT, "I_FIRST", ini, res, run(ini, res)))
    # 3. Link, Contacts-only responder and a contact initiator with the lease tag: R_FIRST with the OPENING extension.
    tag_key = contact_tag_key(LEASE_KEY)
    wrong_epoch = advert_binding(OPENING_ENTRY[:6], 0, 1990656)      # a version 2 style binding that must not match
    ini, res = make_pair(PURPOSE_LINK, "v3", mode="CONTACTS_ONLY", init_tag=(tag_key, VECTOR_BINDING),
                         resp_tag_checks=[(contact_tag_key(bytes(32)), VECTOR_BINDING), (tag_key, wrong_epoch), (tag_key, VECTOR_BINDING)],
                         i_ext=ext_area([(EXT_BUILD_TAG, BUILD_TAG_I)]),
                         r_ext=ext_area([(EXT_OPENING, opening_value([OPENING_ENTRY])), (EXT_BUILD_TAG, BUILD_TAG_R)]))
    msgs = run(ini, res)
    assert res.tag_matched and ini.peer_ext[EXT_OPENING] == opening_value([OPENING_ENTRY])
    assert ini.peer_ext[EXT_BUILD_TAG] == BUILD_TAG_R and res.peer_ext[EXT_BUILD_TAG] == BUILD_TAG_I
    vectors.append(vector_record("link_contact_tag_opening", PURPOSE_LINK, "R_FIRST", ini, res, msgs))
    # 4. Endpoint, both ends present a pseudonym (rule P, no tag): R_FIRST, no OPENING, then the two reveals.
    pe = ext_area([(EXT_PSEUDONYM, b"")])
    ini, res = make_pair(PURPOSE_ENDPOINT, "v4", identity_labels=("pseudonym/I", "pseudonym/R"), i_ext=pe, r_ext=pe)
    msgs = run(ini, res)
    assert EXT_PSEUDONYM in ini.peer_ext and EXT_PSEUDONYM in res.peer_ext and EXT_OPENING not in ini.peer_ext
    record = vector_record("endpoint_pseudonyms", PURPOSE_ENDPOINT, "R_FIRST", ini, res, msgs)
    record["reveal"] = reveal_vectors(ini, res)
    vectors.append(record)
    constants = {
        "domain_link": domain(PURPOSE_LINK).hex(),
        "domain_endpoint": domain(PURPOSE_ENDPOINT).hex(),
        "contact_tag_info": TAG_INFO.hex(),
        "lease_key": LEASE_KEY.hex(),
        "k_tag": tag_key.hex(),
        "vector_binding": VECTOR_BINDING.hex(),
        "sig_context_prefix_link_initiator": sig_context(PURPOSE_LINK, "Initiator", b"").hex(),
        "sig_context_prefix_endpoint_responder": sig_context(PURPOSE_ENDPOINT, "Responder", b"").hex(),
        "hkdf_info_link_hs_i2r": (domain(PURPOSE_LINK) + b"/hs i2r").hex(),
        "sizes": {"msg1": MSG1_BYTES, "msg2_clear": MSG2_CLEAR_BYTES, "block": BLOCK_BYTES},
    }
    return {"generator": "tools/bmh3_reference.py", "constants": constants, "vectors": vectors,
            "negative": negative_cases()}


def kotlin_fixture(doc: dict) -> str:
    lines = [
        "package com.example.bluetoothtest",
        "",
        "/**",
        " * GENERATED by tools/bmh3_reference.py --kotlin (do not edit). Byte-exact BMH3 vectors; tools/tests/",
        " * test_bmh3_reference.py fails when this file differs from the generator's output.",
        " */",
        "internal object Bmh3Vectors {",
        f'    const val LEASE_KEY = "{doc["constants"]["lease_key"]}"',
        f'    const val K_TAG = "{doc["constants"]["k_tag"]}"',
        f'    const val BINDING = "{doc["constants"]["vector_binding"]}"',
        f'    const val OPENING_ENTRY = "{OPENING_ENTRY.hex()}"',
        "",
        "    class Vector(",
        "        val name: String,",
        "        val purpose: String,",
        "        val order: String,",
        "        val inputs: Map<String, String>,",
        "        val messages: Map<String, String>,",
        "        val trace: Map<String, String>,",
        "        val bms1FirstRecordI2r: String,",
        "        val bms1FirstRecordR2i: String,",
        "        /** Pseudonymous vectors only: the two reveal records and what they were built from. */",
        "        val reveal: Map<String, String> = emptyMap(),",
        "    )",
        "",
        "    val all: List<Vector> = listOf(",
    ]
    for v in doc["vectors"]:
        inputs = {}
        for k, val in v["inputs"].items():
            inputs[k] = ",".join(val) if isinstance(val, list) else str(val).lower() if isinstance(val, bool) else str(val)
        trace = {}
        for key, value in {**{f"i_{k}": x for k, x in v["initiator_trace"].items()},
                           **{f"r_{k}": x for k, x in v["responder_trace"].items()}}.items():
            trace[key] = value
        def kmap(m):
            return "mapOf(" + ", ".join(f'"{k}" to "{val}"' for k, val in m.items()) + ")"
        lines += [
            "        Vector(",
            f'            name = "{v["name"]}",',
            f'            purpose = "{v["purpose"]}",',
            f'            order = "{v["order"]}",',
            f"            inputs = {kmap(inputs)},",
            f"            messages = {kmap(v['messages'])},",
            f"            trace = {kmap(trace)},",
            f'            bms1FirstRecordI2r = "{v["bms1_first_record_i2r"]}",',
            f'            bms1FirstRecordR2i = "{v["bms1_first_record_r2i"]}",',
        ] + ([f"            reveal = {kmap(v['reveal'])},"] if "reveal" in v else []) + [
            "        ),",
        ]
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    doc = build_vectors()
    for v in doc["vectors"]:
        print(f"{v['name']}: {v['order']} lengths {v['message_lengths']}  app_i2r={v['initiator_trace']['app_i2r'][:16]}...")
    for n in doc["negative"]:
        print(f"NEG {n['case']}: {n['result']} ({n['reason']})")
    if "--json" in argv:
        path = argv[argv.index("--json") + 1]
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(doc, handle, indent=1)
        print(f"wrote {path}")
    if "--kotlin" in argv:
        path = argv[argv.index("--kotlin") + 1]
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(kotlin_fixture(doc))
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
