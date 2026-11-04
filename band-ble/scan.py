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

    def _rssi(d):
        # bleak 1.x: BLEDevice 无 rssi 属性，改从 advertisement_data 取
        v = getattr(d, "rssi", None)
        if v is None:
            adv = getattr(d, "advertisement_data", None)
            v = getattr(adv, "rssi", None) if adv is not None else None
        return v if v is not None else -999

    for d in sorted(devices, key=_rssi, reverse=True):
        name = d.name or "(no name)"
        rssi = _rssi(d)
        rssi = rssi if rssi != -999 else "?"
        addr = d.address
        meta = getattr(d, "metadata", None) or {}
        uuids = [str(u) for u in meta.get("uuids", [])]
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