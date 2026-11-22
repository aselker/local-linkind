"""Verified AiDot/Leedarson BLE local primitives — no cloud account needed.

Scheme (reverse-engineered from AiDot 2.27.1, com.leedarson.*):
  base_mac   = BLE address with last octet - 2 (ESP pattern); matches advertised name/mfr-data
  prov_key   = ("DF123EFA234569823098" + base_mac_UPPERCASE_nocolons)  [32 bytes -> AES-256]
  read/write = AES-256-CTR, IV = first 16 bytes of the char value (device nonce), ct = rest
  deviceId   = field "D" of getDeviceInfo (char a00b)
"""
import asyncio
import json
import sys

from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

SVC = "5cfefeab-cdc1-4a6d-b1af-6f18294c99bc"
CHARS = {
    "getDeviceInfo": "5cfefeab-cdc1-4a6d-b1af-6f18294ca00b",
    "a00c_status": "5cfefeab-cdc1-4a6d-b1af-6f18294ca00c",
    "getDeviceId": "5cfefeab-cdc1-4a6d-b1af-6f18294ca005",
}
PREFIX = "DF123EFA234569823098"


def base_mac(ble_addr: str) -> str:
    b = bytearray(bytes.fromhex(ble_addr.replace(":", "")))
    b[-1] = (b[-1] - 2) & 0xFF
    return b.hex().upper()


def prov_key(ble_addr: str) -> bytes:
    return (PREFIX + base_mac(ble_addr)).encode()[:32].ljust(32, b"\0")


def decrypt_read(raw: bytes, key: bytes) -> bytes:
    iv, ct = raw[:16], raw[16:]
    return Cipher(algorithms.AES(key), modes.CTR(iv)).decryptor().update(ct)


async def main(addr: str) -> None:
    key = prov_key(addr)
    print(f"BLE addr : {addr}")
    print(f"base MAC : {base_mac(addr)}")
    print(f"prov key : {key.decode(errors='replace')}")
    dev = await BleakScanner.find_device_by_address(addr, timeout=15)
    if dev is None:
        sys.exit(f"{addr} not found")
    async with BleakClient(dev, timeout=25) as client:
        for name, uuid in CHARS.items():
            try:
                raw = bytes(await client.read_gatt_char(uuid))
            except Exception as e:  # noqa: BLE001
                print(f"\n{name}: read failed: {e}")
                continue
            pt = decrypt_read(raw, key)
            text = pt.split(b"\x00", 1)[0].decode(errors="replace")
            print(f"\n== {name} ({len(raw)}B) ==")
            print("  plaintext:", text)
            if text.startswith("{"):
                try:
                    print("  parsed   :", json.dumps(json.loads(text), indent=2))
                except Exception:
                    pass


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "AA:BB:CC:DD:EE:F2"))
