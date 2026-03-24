#!/usr/bin/env python3
# Run from depchain/: python derive_addresses.py
# Requires: pip install cryptography pycryptodome

from cryptography.hazmat.primitives.serialization import load_pem_public_key
from Crypto.Hash import keccak

def derive_address(pem_path):
    with open(pem_path, "rb") as f:
        key = load_pem_public_key(f.read())
    nums = key.public_numbers()
    xy = nums.x.to_bytes(32, "big") + nums.y.to_bytes(32, "big")
    k = keccak.new(digest_bits=256)
    k.update(xy)
    return "0x" + k.digest()[12:].hex()

clients = ["client1", "client2"]
for c in clients:
    print(f"{c}: {derive_address(f'client/keystore/{c}/public.pem')}")
