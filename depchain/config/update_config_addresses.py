#!/usr/bin/env python3
"""
Derives blockchain addresses from keystores and updates config files.
Addresses are derived from EC public keys using keccak256(uncompressed_coords)[12:32].

Usage: python update_config_addresses.py
Expected to be run from depchain/ directory.

Required packages: pip install cryptography pycryptodome
"""

import json
import os
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from Crypto.Hash import keccak


def derive_address(pem_path):
    """
    Derives a blockchain address from an EC public key.
    Algorithm matches AddressUtils.java:
    - Extract X, Y coordinates (32 bytes each)
    - Compute keccak256(X || Y)
    - Take bytes [12:32] (20 bytes)
    - Return as hex string with 0x prefix
    """
    with open(pem_path, "rb") as f:
        key = load_pem_public_key(f.read())
    
    nums = key.public_numbers()
    # Uncompressed format: X (32 bytes) || Y (32 bytes)
    xy = nums.x.to_bytes(32, "big") + nums.y.to_bytes(32, "big")
    
    # Keccak256 hash
    k = keccak.new(digest_bits=256)
    k.update(xy)
    digest = k.digest()
    
    # Take bytes 12:32 (20 bytes)
    address_bytes = digest[12:32]
    return "0x" + address_bytes.hex()


def load_config(config_path):
    """Load JSON config file."""
    with open(config_path, "r") as f:
        return json.load(f)


def save_config(config_path, config_data):
    """Save JSON config file with indentation."""
    with open(config_path, "w") as f:
        json.dump(config_data, f, indent=2)
    print(f"Updated: {config_path}")


def main():
    # Get the directory where this script is run from (depchain/)
    script_dir = Path(__file__).parent.resolve()
    
    # Derive addresses for all entities
    addresses = {}
    
    # Client addresses
    clients = ["client1", "client2"]
    for client in clients:
        pem_path = script_dir / "client" / "keystore" / client / "public.pem"
        if pem_path.exists():
            addresses[client] = derive_address(str(pem_path))
            print(f"{client}: {addresses[client]}")
        else:
            print(f"! Missing: {pem_path}")
    
    # Server addresses
    servers = ["s0", "s1", "s2", "s3"]
    for server in servers:
        pem_path = script_dir / "core" / "keystore" / server / "public.pem"
        if pem_path.exists():
            addresses[server] = derive_address(str(pem_path))
            print(f"{server}: {addresses[server]}")
        else:
            print(f"! Missing: {pem_path}")
    
    # Update config files
    config_files = ["config-dev.json", "config-tamper.json", "config-test.json"]
    
    for config_file in config_files:
        config_path = script_dir / config_file
        if not config_path.exists():
            print(f"! Config file not found: {config_path}")
            continue
        
        try:
            config = load_config(str(config_path))
            
            # Update aliases in blockchainConfig
            if "blockchainConfig" not in config:
                config["blockchainConfig"] = {}
            if "aliases" not in config["blockchainConfig"]:
                config["blockchainConfig"]["aliases"] = {}
            
            aliases = config["blockchainConfig"]["aliases"]
            
            # Update all derived addresses
            for entity, address in addresses.items():
                aliases[entity] = address
            
            save_config(str(config_path), config)
            
        except Exception as e:
            print(f"! Error updating {config_file}: {e}")
    
    print("\n" + "="*60)
    print("Summary of derived addresses:")
    print("="*60)
    for entity in sorted(addresses.keys()):
        print(f"{entity:12} {addresses[entity]}")


if __name__ == "__main__":
    main()
