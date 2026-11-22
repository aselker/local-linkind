"""Account-free local control of Linkind / AiDot (Leedarson) smart bulbs over BLE.

These bulbs (e.g. Linkind LS01017116 RGBTW) are normally set up and controlled
through the AiDot cloud app.  This module talks to them directly over Bluetooth
LE so you can own and automate your own bulbs without ever creating an account.

Everything here was reverse-engineered from the official AiDot app (2.27.1,
com.iotsolution.aidot, OEM Leedarson) and verified against real hardware.  See
FINDINGS.md / aidot_ble.py for the provisioning-read crypto; this file adds the
runtime *command* channel (turn on/off, brightness, colour, colour-temperature).

The runtime channel works like this (chars on service 5cfefeab-…-6f18294c99bc):

    write  -> b001   notify <- b002

    frame        = [seq, 0x11, dst, dst, category, command] + payload
    sign-in      = category 01 / cmd 10, sent in the clear:
                     "nghx8qug3lkl5oyd" + AES256CTR(k, iv="nghx8qug3lkl5oyd",
                                                     "nghx8qug3lkl5oyd")
                   device acks with command 0x90, status 00.
    after sign-in every frame is AES-256-CTR(k, iv="nghx8qug3lkl5oyd").
    k (aesKey)   = sha256("{modelId},{mac_lower},{deviceId},nghx8qug3lkl5oyd")
                     [:16].hex()                       # 32 ASCII chars == 32-byte key

    setAttrs     = category 03 / cmd 02, payload = TLV:
                     count + for each attr:  id(2B) type(1B) len(1B) value
                   OnOff  id 0028  bool  (01/00)
                   Dimming id 0027 1B    (0..100)
                   CCT     id 0038 2B    (kelvin, big-endian)
                   RGBW    id 0056 3B    (rr gg bb)

The deviceId comes from getDeviceInfo (char a00b).  The only value not stored on
the device is the product `modelId` (needed for the sign-in key); resolve_model()
tries the known Linkind light model-ids and keeps the one the bulb accepts.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import logging
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

log = logging.getLogger("aidot")

# ---- GATT ------------------------------------------------------------------
SVC = "5cfefeab-cdc1-4a6d-b1af-6f18294c99bc"
CH_DEVINFO = "5cfefeab-cdc1-4a6d-b1af-6f18294ca00b"  # getDeviceInfo (read)
CH_CMD_W = "5cfefeab-cdc1-4a6d-b1af-6f18294cb001"    # command write
CH_CMD_N = "5cfefeab-cdc1-4a6d-b1af-6f18294cb002"    # command notify
MFR_ID = 0x6811                                      # manufacturer-data company id

# ---- crypto constants ------------------------------------------------------
PROV_PREFIX = "DF123EFA234569823098"      # provisioning-read key prefix
SIGN_CONST = b"nghx8qug3lkl5oyd"          # hard-coded sign-in nonce / IV (16 bytes)


# ---------------------------------------------------------------------------
# key / crypto helpers
# ---------------------------------------------------------------------------
def base_mac(ble_addr: str) -> str:
    """Base MAC = BLE address with the last octet - 2 (ESP pattern).

    Equals the MAC shown in the advertised name / manufacturer data.
    """
    b = bytearray(bytes.fromhex(ble_addr.replace(":", "")))
    b[-1] = (b[-1] - 2) & 0xFF
    return b.hex().upper()


def prov_key(ble_addr: str) -> bytes:
    """AES-256 key for the provisioning read/write chars (a00a/a00b/a00c)."""
    return (PROV_PREFIX + base_mac(ble_addr)).encode()[:32].ljust(32, b"\0")


def command_key(model_id: str, ble_addr: str, device_id: str) -> bytes:
    """AES-256 key for the runtime command channel (b001/b002).

    k = sha256("modelId,mac_lower_nocolons,deviceId,nghx8qug3lkl5oyd")[:16].hex()
    The 32 lowercase hex characters are used as the raw 32 key bytes.
    """
    mac = base_mac(ble_addr).lower()  # command channel uses the base (advertised) MAC
    material = f"{model_id},{mac},{device_id},{SIGN_CONST.decode()}"
    return hashlib.sha256(material.encode()).digest()[:16].hex().encode()


def _ctr(key: bytes, iv: bytes, data: bytes) -> bytes:
    return Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor().update(data)


def decrypt_read(raw: bytes, key: bytes) -> bytes:
    """Provisioning read: [16-byte ASCII nonce IV | AES-256-CTR ciphertext]."""
    return _ctr(key, raw[:16], raw[16:])


# ---------------------------------------------------------------------------
# attribute (TLV) encoding  -- mirrors the app's attrsToHex / hexToAttrs
# ---------------------------------------------------------------------------
_BOOL, _NUM = "00", "01"
# name -> (id hex, byte length, type)
ATTRS: dict[str, tuple[str, int, str]] = {
    "OnOff": ("0028", 1, _BOOL),
    "Dimming": ("0027", 1, _NUM),   # 0..100 (%)
    "CCT": ("0038", 2, _NUM),       # kelvin
    "RGBW": ("0056", 3, _NUM),      # rrggbb
}
_ATTR_BY_ID = {v[0]: (k, v[1], v[2]) for k, v in ATTRS.items()}


def encode_attrs(attrs: dict[str, object]) -> bytes:
    """{'OnOff':1,'Dimming':50} -> TLV bytes (count + id/type/len/value each)."""
    out = f"{len(attrs):02x}"
    for name, value in attrs.items():
        aid, nbytes, atype = ATTRS[name]
        if atype == _BOOL:
            val = "01" if value else "00"
        elif isinstance(value, str):          # RGBW passed as "rrggbb"
            val = value
        else:
            val = format(int(value), "x")
        val = val.rjust(nbytes * 2, "0")
        out += aid + atype + f"{nbytes:02x}" + val
    return bytes.fromhex(out)


def decode_attrs(payload: bytes) -> dict[str, object]:
    """Inverse of encode_attrs for notify/response payloads (best effort)."""
    hexs = payload.hex()
    out: dict[str, object] = {}
    if len(hexs) < 2:
        return out
    i = 2  # skip count byte
    while i + 8 <= len(hexs):
        aid = hexs[i : i + 4]
        info = _ATTR_BY_ID.get(aid)
        if not info:
            break
        name, nbytes, atype = info
        raw = hexs[i + 8 : i + 8 + nbytes * 2]
        out[name] = (raw == "01") if atype == _BOOL else int(raw, 16)
        i += 8 + nbytes * 2
    return out


# ---------------------------------------------------------------------------
# command framing
# ---------------------------------------------------------------------------
def build_frame(seq: int, category: int, command: int, payload: bytes = b"") -> bytes:
    # [seq, packetFlag(0x11 = 1 chunk of 1), dst, dst, category, command] + payload
    return bytes([seq & 0xFF, 0x11, 0x00, 0x00, category, command]) + payload


@dataclass
class Response:
    seq: int
    category: int
    command: int
    status: int
    payload: bytes

    @classmethod
    def parse(cls, raw: bytes) -> "Response":
        # [seq, flag, srcId, dstId, category, command, status] + payload
        return cls(raw[0], raw[4], raw[5], raw[6], raw[7:])

    @property
    def ok(self) -> bool:
        return self.status == 0x00


# known Linkind light model-ids, used to recover the per-product sign-in key
DEFAULT_MODEL_CANDIDATES: tuple[str, ...] = ()  # filled from a sidecar file if present


# ---------------------------------------------------------------------------
# the bulb
# ---------------------------------------------------------------------------
class AidotBulb:
    def __init__(self, device: BLEDevice | str):
        self._device = device
        self.address = device.address if isinstance(device, BLEDevice) else device
        self.client: BleakClient | None = None
        self.device_id: str | None = None
        self.model_id: str | None = None
        self._key: bytes | None = None
        self._signed_in = False
        self._seq = 0
        self._notifies: asyncio.Queue[bytes] = asyncio.Queue()

    # -- connection ---------------------------------------------------------
    async def __aenter__(self) -> "AidotBulb":
        await self.connect()
        return self

    async def __aexit__(self, *exc) -> None:
        await self.disconnect()

    async def connect(self) -> None:
        dev = self._device
        if not isinstance(dev, BLEDevice):
            found = await BleakScanner.find_device_by_address(self.address, timeout=15)
            if found is None:
                raise RuntimeError(f"{self.address} not found")
            dev = found
        self.client = BleakClient(dev, timeout=25)
        await self.client.connect()
        await self.client.start_notify(
            CH_CMD_N, lambda _h, data: self._notifies.put_nowait(bytes(data))
        )
        await self.read_device_info()

    async def disconnect(self) -> None:
        if self.client and self.client.is_connected:
            await self.client.disconnect()

    # -- reads --------------------------------------------------------------
    async def read_device_info(self) -> dict:
        raw = bytes(await self.client.read_gatt_char(CH_DEVINFO))
        text = decrypt_read(raw, prov_key(self.address)).split(b"\x00", 1)[0]
        info = json.loads(text.decode())
        self.device_id = info.get("D")
        log.debug("device info: %s", info)
        return info

    # -- command channel ----------------------------------------------------
    def _next_seq(self) -> int:
        self._seq = (self._seq + 1) & 0xFF
        return self._seq

    async def _drain(self) -> None:
        while not self._notifies.empty():
            self._notifies.get_nowait()

    async def _exchange(
        self, category: int, command: int, payload: bytes = b"", *,
        encrypt: bool, timeout: float = 3.0,
    ) -> Response | None:
        """Send one frame, return the first decoded response (or None on timeout)."""
        await self._drain()
        frame = build_frame(self._next_seq(), category, command, payload)
        if encrypt:
            assert self._key is not None
            frame = _ctr(self._key, SIGN_CONST, frame)
        await self.client.write_gatt_char(CH_CMD_W, frame, response=True)
        try:
            raw = await asyncio.wait_for(self._notifies.get(), timeout)
        except asyncio.TimeoutError:
            return None
        if self._signed_in and self._key is not None:
            raw = _ctr(self._key, SIGN_CONST, raw)
        return Response.parse(raw)

    async def sign_in(self, model_id: str) -> bool:
        """Authenticate the session for `model_id`. Returns True on device ACK."""
        assert self.device_id, "read_device_info() first"
        self.model_id = model_id
        self._key = command_key(model_id, self.address, self.device_id)
        self._signed_in = False
        payload = SIGN_CONST + _ctr(self._key, SIGN_CONST, SIGN_CONST)
        # sign-in frame itself is sent in the clear
        resp = await self._exchange(0x01, 0x10, payload, encrypt=False)
        if resp and resp.command == 0x90 and resp.ok:
            self._signed_in = True
            log.info("signed in as %s", model_id)
            return True
        log.debug("sign-in %s -> %s", model_id, resp)
        return False

    async def resolve_model(self, candidates: list[str]) -> str | None:
        """Find which product profile the bulb authenticates with.

        The sign-in key is derived from the product model-id, which the bulb
        doesn't report.  Try each known model-id and keep the one it accepts.
        """
        for model_id in candidates:
            if not (self.client and self.client.is_connected):
                log.debug("link dropped, reconnecting before trying %s", model_id)
                await self.connect()
            try:
                if await self.sign_in(model_id):
                    return model_id
            except Exception as exc:  # noqa: BLE001 - keep trying remaining profiles
                log.debug("sign-in %s raised %s", model_id, exc)
        return None

    # -- high-level control -------------------------------------------------
    async def set(self, **attrs: object) -> Response | None:
        """Set one or more attributes, e.g. set(OnOff=1, Dimming=40)."""
        if not self._signed_in:
            raise RuntimeError("sign_in()/resolve_model() required before control")
        return await self._exchange(0x03, 0x02, encode_attrs(attrs), encrypt=True)

    async def turn_on(self):  return await self.set(OnOff=1)
    async def turn_off(self): return await self.set(OnOff=0)
    async def brightness(self, pct: int): return await self.set(OnOff=1, Dimming=max(1, min(100, pct)))
    async def color_temp(self, kelvin: int): return await self.set(OnOff=1, CCT=kelvin)

    async def color(self, r: int, g: int, b: int):
        return await self.set(OnOff=1, RGBW=f"{r:02x}{g:02x}{b:02x}")


# ---------------------------------------------------------------------------
# discovery
# ---------------------------------------------------------------------------
async def discover(timeout: float = 8.0) -> list[tuple[BLEDevice, str]]:
    """Return [(device, short_model_id)] for AiDot bulbs in range."""
    found: dict[str, tuple[BLEDevice, str]] = {}

    def cb(dev: BLEDevice, adv) -> None:
        data = adv.manufacturer_data.get(MFR_ID)
        if not data:
            return
        # manufacturer payload (company id 0x6811 already stripped by bleak):
        #   10 00 <6-byte base-mac> 00 <3-byte shortModelId> ...
        # (the app sees "1168"+payload and reads shortModelId at string offset 20)
        short_model = data.hex()[16:22] if len(data) >= 11 else ""
        found[dev.address] = (dev, short_model)

    async with BleakScanner(cb):
        await asyncio.sleep(timeout)
    return list(found.values())


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def _here(name: str) -> str:
    import os
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), name)


_CACHE = _here(".aidot_models.json")


def cache_get(address: str) -> str | None:
    import os
    if not os.path.exists(_CACHE):
        return None
    try:
        with open(_CACHE) as fh:
            return json.load(fh).get(address.upper())
    except (OSError, ValueError):
        return None


def cache_put(address: str, model_id: str) -> None:
    data: dict[str, str] = {}
    try:
        with open(_CACHE) as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        pass
    data[address.upper()] = model_id
    try:
        with open(_CACHE, "w") as fh:
            json.dump(data, fh, indent=2)
    except OSError:
        pass


def _load_candidates() -> list[str]:
    import os
    path = _here("model_ids.txt")
    raw: list[str] = list(DEFAULT_MODEL_CANDIDATES)
    if os.path.exists(path):
        with open(path) as fh:
            raw = [ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")]
    # The exact case used in the sign-in SHA-256 is uncertain, so try each
    # candidate verbatim and lowercased (de-duplicated, order preserved).
    out: list[str] = []
    for m in raw:
        for variant in (m, m.lower()):
            if variant not in out:
                out.append(variant)
    return out


COMMANDS = ("discover", "info", "resolve", "on", "off", "brightness", "cct", "color")
_NEEDS_SIGNIN = ("on", "off", "brightness", "cct", "color")


async def _cli() -> None:
    # Positional order is ADDRESS then COMMAND (e.g. "<addr> --model X brightness 20").
    # Avoiding argparse subparsers here so that --model can appear anywhere on the
    # line without being mistaken for the command.
    p = argparse.ArgumentParser(
        description="Account-free BLE control of AiDot/Linkind bulbs",
        usage="%(prog)s [--model ID] [-v] ADDRESS COMMAND [args]\n"
              "       %(prog)s discover\n"
              f"commands: {', '.join(COMMANDS)}",
    )
    p.add_argument("--model", help="product model-id (skip auto-resolve)")
    p.add_argument("-v", "--verbose", action="store_true")
    p.add_argument("address", nargs="?", help="BLE address of the bulb")
    p.add_argument("command", nargs="?", help="one of: " + ", ".join(COMMANDS))
    p.add_argument("params", nargs="*", help="command arguments (e.g. brightness pct)")
    args = p.parse_args()

    # "discover" takes no address, so it lands in the address slot; normalise.
    address, command, params = args.address, args.command, args.params
    if address in COMMANDS and command is None and not params:
        address, command = None, address

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(message)s")

    if command is None or command == "discover":
        for dev, short in await discover():
            print(f"{dev.address}  {dev.name or '?'}  shortModel={short}")
        return
    if command not in COMMANDS:
        p.error(f"unknown command {command!r}; choose from {', '.join(COMMANDS)}")
    if not address:
        p.error("address required")

    def ints(n: int) -> list[int]:
        if len(params) != n:
            p.error(f"{command} needs {n} argument(s), got {len(params)}")
        try:
            return [int(x) for x in params]
        except ValueError:
            p.error(f"{command} arguments must be integers")

    async with AidotBulb(address) as bulb:
        if command == "info":
            print(json.dumps(await bulb.read_device_info(), indent=2))
            return

        model = args.model
        # A cached model-id (from a previous resolve) lets control commands skip
        # straight to sign-in; --model always overrides the cache.
        if model is None and command != "resolve":
            cached = cache_get(address)
            if cached and await bulb.sign_in(cached):
                model = cached

        if model is None or (command == "resolve" and not args.model):
            cands = [args.model] if args.model else _load_candidates()
            if not cands:
                p.error("no model-ids to try (pass --model or create model_ids.txt)")
            model = await bulb.resolve_model(cands)
            if not model:
                print("could not resolve model-id (bulb rejected all candidates)")
                return
            cache_put(address, model)
            print(f"model-id: {model}")
            if command == "resolve":
                return
        elif command in _NEEDS_SIGNIN and not bulb._signed_in:
            if not await bulb.sign_in(model):
                print(f"sign-in failed for model {model}")
                return

        if command == "on":
            r = await bulb.turn_on()
        elif command == "off":
            r = await bulb.turn_off()
        elif command == "brightness":
            r = await bulb.brightness(ints(1)[0])
        elif command == "cct":
            r = await bulb.color_temp(ints(1)[0])
        elif command == "color":
            rgb = ints(3)
            r = await bulb.color(*rgb)
        else:
            return
        print("ok" if (r and r.ok) else f"response: {r}")


if __name__ == "__main__":
    asyncio.run(_cli())
