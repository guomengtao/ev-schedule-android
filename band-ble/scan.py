#!/usr/bin/env python3
"""Minimal BLE scanner for Xiaomi Band discovery via bleak."""

import asyncio
from bleak import BleakScanner


async def main():
    print("Scanning for BLE devices (5 seconds)...")
    print("-" * 72)

    devices = await BleakScanner.discover(timeout=5.0)

    if not devices:
        print("No devices found.")
        print("Check: is Bluetooth on? Is the band nearby and disconnected from phone?")
        return

    for d in sorted(devices, key=lambda x: x.rssi or -999, reverse=True):
        name = d.name or "(no name)"
        rssi = d.rssi if d.rssi is not None else "?"
        addr = d.address
        uuids = [str(u) for u in d.metadata.get("uuids", [])] if d.metadata else []
        flag = ""
        if "band" in name.lower() or "xiaomi" in name.lower() or "mi " in name.lower():
            flag = "  <<<< BAND!"
        print(f"  {name:40s}  RSSI={str(rssi):>4s}  {addr}")
        if uuids:
            print(f"    advertised UUIDs: {uuids}")
        if flag:
            print(f"  {flag}")

    print("-" * 72)
    print("Done.")


asyncio.run(main())