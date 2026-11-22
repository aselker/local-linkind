"""Read getDeviceInfo (a00b) + a00c from a bulb and try to decrypt per the RE'd scheme:
AES-256-CTR, key = ('DF123EFA234569823098'+mac)[:32], IV = first 16 bytes of the value."""
import asyncio
import sys

from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

SVC = "5cfefeab-cdc1-4a6d-b1af-6f18294c99bc"
A00B = "5cfefeab-cdc1-4a6d-b1af-6f18294ca00b"  # getDeviceInfo
A00C = "5cfefeab-cdc1-4a6d-b1af-6f18294ca00c"
PREFIX = "DF123EFA234569823098"


def key_variants(mac: str):
    bare = mac.replace(":", "")
    # base MAC = BLE address - 2 in the last octet (ESP pattern); advert/name shows ...EEF0
    try:
        base = bytearray(bytes.fromhex(bare))
        base[-1] = (base[-1] - 2) & 0xFF
        base_hex = base.hex()
    except Exception:
        base_hex = bare
    cands = {
        "bleaddr-lower12": bare.lower(),
        "bleaddr-UPPER12": bare.upper(),
        "bleaddr-colonUP": mac.upper(),
        "basemac-lower12": base_hex.lower(),
        "basemac-UPPER12": base_hex.upper(),
        "basemac-colonUP": ":".join(base_hex[i:i+2] for i in range(0, 12, 2)).upper(),
    }
    for k, v in cands.items():
        yield k, v


def aes256ctr(key32: bytes, iv16: bytes, ct: bytes) -> bytes:
    return Cipher(algorithms.AES(key32), modes.CTR(iv16)).decryptor().update(ct)


def try_decrypt(label: str, raw: bytes, mac: str):
    print(f"\n### {label}: {len(raw)} bytes")
    print("raw    :", raw.hex(" "))
    print("ascii16:", raw[:16])
    for name, macstr in key_variants(mac):
        keystr = (PREFIX + macstr).encode()
        key32 = keystr[:32].ljust(32, b"\0")
        # Hypothesis A: IV = first 16 bytes (device-chosen nonce in cleartext), ct = rest
        iv = raw[:16]
        pt = aes256ctr(key32, iv, raw[16:])
        printable = sum(32 <= b < 127 for b in pt)
        flag = "  <== looks like text/JSON" if (b"{" in pt or b"=" in pt or printable > len(pt) * 0.8) else ""
        print(f"  [{name}] keylen={len(keystr)} ivFirst16 -> {pt[:48]!r}{flag}")
        # Hypothesis B: IV = zero, whole buffer is ciphertext
        pt2 = aes256ctr(key32, b"\0" * 16, raw)
        if b"{" in pt2[:32]:
            print(f"  [{name}] ivZero          -> {pt2[:48]!r}  <== JSON")


async def main(addr: str) -> None:
    dev = await BleakScanner.find_device_by_address(addr, timeout=15)
    if dev is None:
        sys.exit(f"{addr} not found")
    async with BleakClient(dev, timeout=25) as client:
        print(f"Connected {addr}")
        for name, uuid in (("a00b/getDeviceInfo", A00B), ("a00c", A00C)):
            try:
                raw = bytes(await client.read_gatt_char(uuid))
                try_decrypt(name, raw, addr)
            except Exception as e:  # noqa: BLE001
                print(f"{name}: read failed: {e}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "AA:BB:CC:DD:EE:F2"))
