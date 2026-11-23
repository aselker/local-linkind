# Account-free BLE control for Linkind / AiDot bulbs

Control Linkind / AiDot (Leedarson) smart bulbs — e.g. the **LS01017116 RGBTW** —
directly over Bluetooth LE, with **no AiDot cloud account**. The official app sets
these up through the cloud; this talks to the bulb's own BLE service instead, so
you can own and automate your lights locally.

The whole protocol was reverse-engineered from the AiDot app (2.27.1,
`com.iotsolution.aidot`, OEM Leedarson) and verified against real hardware. See
[`FINDINGS.md`](FINDINGS.md) for the full provisioning write-up and
[`aidot_control.py`](aidot_control.py)'s module docstring for the command protocol.

Vibecoded entirely by Claude Opus 4.8 and 5.5. "To one who has faith in Claude, no explanation is necessary; to one without faith, no explanation is possible."

## Install

```sh
python3 -m venv .venv
.venv/bin/pip install bleak cryptography
```

## Use

```sh
# find bulbs in range
.venv/bin/python aidot_control.py discover

# read device info (no auth needed)
.venv/bin/python aidot_control.py <ADDR> info

# first time: discover which product profile the bulb uses (see "model-id" below)
.venv/bin/python aidot_control.py <ADDR> resolve

# control it (model-id is cached after the first resolve/‑‑model, so you can omit it)
.venv/bin/python aidot_control.py <ADDR> on
.venv/bin/python aidot_control.py <ADDR> off
.venv/bin/python aidot_control.py <ADDR> brightness 20      # 1..100 %
.venv/bin/python aidot_control.py <ADDR> cct 2700           # kelvin
.venv/bin/python aidot_control.py <ADDR> color 255 0 0      # r g b (0..255)
```

`<ADDR>` is the BLE address from `discover` (e.g. `AA:BB:CC:DD:EE:F2`).
Add `-v` for debug logging (raw device replies). Pass `--model <ID>` anywhere on
the line to override the cached/auto-detected model-id.

### As a library

```python
import asyncio
from aidot_control import AidotBulb

async def main():
    async with AidotBulb("AA:BB:CC:DD:EE:F2") as bulb:
        await bulb.sign_in("LK.light.A001577")   # or bulb.resolve_model(candidates)
        await bulb.brightness(40)
        await bulb.color(0, 128, 255)

asyncio.run(main())
```

## The one quirk: `model-id`

Each command session is authenticated with a key derived from the product's
`modelId` string — which the bulb itself doesn't report (it normally comes from
the cloud catalog). The tool recovers it by trying the known Linkind light
model-ids (`model_ids.txt`) until the bulb accepts one, via `resolve`. The result
is cached per-address in `.aidot_models.json`, so you only resolve once.

Verified mapping:

| bulb SKU      | BLE adv `shortModelId` | `modelId`          |
|---------------|------------------------|--------------------|
| LS01017116    | `00002a`               | `LK.light.A001577` |

A different SKU just needs its own one-time `resolve`.

## Scope

- **Does:** discovery, device-info read, authenticated BLE control (on/off,
  brightness, colour temperature, RGB).
- **Doesn't:** WiFi provisioning (`setWifiInfo2`). Not needed for local BLE
  control — and note the WiFi/TCP control path (python-aidot) authenticates with
  cloud-account-derived secrets, so **BLE is the genuinely account-free route**.

## Android app

[`android/`](android/) is a native Kotlin port of this protocol as an Android app: provision
bulbs account-free over BLE and control them (on/off, brightness, colour-temperature) from a
low-resource home-screen **widget** (no background work at idle; one short BLE op per tap). Bulbs
are organised into **groups** — the widget shows one control block per group and a tap fans out to
every bulb in it; a group can hold a single bulb or several. The brightness/CCT presets are
configured per group in the app. See [`android/README.md`](android/README.md).

## Files

- `aidot_control.py` — the control library + CLI (start here).
- `aidot_ble.py` — the verified provisioning-read primitives.
- `model_ids.txt` — candidate Linkind light model-ids for `resolve`.
- `FINDINGS.md` — full reverse-engineering notes and provenance.
- `gatt_dump.py`, `verify_deviceinfo.py` — RE helpers.
- `android/` — Android app + home-screen widget (Kotlin port of the protocol).

## License & disclaimer

MIT — see [`LICENSE`](LICENSE). Use it as-is, or build on the reverse-engineered
protocol for your own projects.

This is an independent, unofficial project. It is **not affiliated with, endorsed
by, or supported by** AiDot, Linkind, or Leedarson. "AiDot" and "Linkind" are the
trademarks of their respective owners, used here only to identify the hardware this
code interoperates with. The protocol was reverse-engineered from the vendor's own
app for interoperability with bulbs you own; the vendor's app is not redistributed
here. No warranty — you run it at your own risk.

