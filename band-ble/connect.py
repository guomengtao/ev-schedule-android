#!/usr/bin/env python3
"""Direct-connect to Xiaomi Band 9 via bleak, enumerate GATT services."""

import asyncio
from bleak import BleakClient

BAND_MAC = "04:34:C3:06:B8:85"
AUTHKEY_HEX = "e2bfe55361716796bcde1b45749db7a9"


async def main():
    print(f"Connecting to {BAND_MAC} ...")

    async with BleakClient(BAND_MAC) as client:
        print(f"Connected: {client.is_connected}")

        if not client.is_connected:
            print("Failed to connect.")
            return

        print("\nGATT Services:")
        print("-" * 72)

        for svc in client.services:
            print(f"svc  {svc.uuid}")
            for ch in svc.characteristics:
                props_list = ch.properties
                props_str = ",".join(sorted(props_list))
                descs = len(ch.descriptors)
                extra = f"  descs={descs}" if descs > 0 else ""
                print(f"  ch  {ch.uuid}  [{props_str}]{extra}")

                if "read" in props_list:
                    try:
                        val = await client.read_gatt_char(ch.uuid)
                        print(f"    read ({len(val)}B): {val.hex()}")
                    except Exception as e:
                        print(f"    read ERROR: {e}")

        print("-" * 72)
        print("Done.")


asyncio.run(main())