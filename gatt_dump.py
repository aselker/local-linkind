"""Connect to a BLE device and dump its full GATT table, reading every readable characteristic."""
import asyncio
import sys

from bleak import BleakClient, BleakScanner


async def main(addr: str) -> None:
    dev = await BleakScanner.find_device_by_address(addr, timeout=15)
    if dev is None:
        sys.exit(f"{addr} not found")
    async with BleakClient(dev, timeout=20) as client:
        print(f"Connected to {addr}, MTU={client.mtu_size}")
        for svc in client.services:
            print(f"\n[service] {svc.uuid}  ({svc.description})  handle={svc.handle}")
            for ch in svc.characteristics:
                props = ",".join(ch.properties)
                print(f"  [char] {ch.uuid}  handle={ch.handle}  props={props}  ({ch.description})")
                if "read" in ch.properties:
                    try:
                        val = await client.read_gatt_char(ch)
                        print(f"         value: {val.hex(' ')}  {val!r}")
                    except Exception as e:  # noqa: BLE001
                        print(f"         read failed: {e}")
                for d in ch.descriptors:
                    try:
                        dv = await client.read_gatt_descriptor(d.handle)
                        print(f"    [desc] {d.uuid} handle={d.handle} value={dv.hex(' ')}")
                    except Exception as e:  # noqa: BLE001
                        print(f"    [desc] {d.uuid} handle={d.handle} read failed: {e}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "AA:BB:CC:DD:EE:F2"))
