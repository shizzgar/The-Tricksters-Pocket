#!/usr/bin/env python3
"""Filter saved host/IP/HTTP(S) candidates by explicit scope; no DNS or scans."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

MAX_INPUT = 4 * 1024 * 1024


def host(value):
    value = value.strip()
    if not value or value.startswith("-") or any(c.isspace() or ord(c) < 32 for c in value):
        raise ValueError("Expected a host, IP or HTTP(S) URL")
    if "://" in value:
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or parsed.username is not None or parsed.password is not None:
            raise ValueError("Only HTTP(S) URLs without credentials are accepted")
        if not parsed.hostname:
            raise ValueError("URL has no hostname")
        parsed.port  # Reject malformed ports.
        return host(parsed.hostname)
    try:
        return str(ipaddress.ip_address(value))
    except ValueError:
        pass
    # Bare host:port is not a hostname; require a URL to make ports unambiguous.
    value = (value[:-1] if value.endswith(".") else value).encode("idna").decode("ascii").lower()
    if len(value) > 253 or not all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in value.split(".")):
        raise ValueError("Invalid hostname")
    # Reject ambiguous legacy numeric IPv4 notation rather than treating it as DNS.
    if re.fullmatch(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*", value):
        raise ValueError("Use canonical IP address notation")
    return value


def rule(value):
    if "/" in value and "://" not in value:
        try:
            return ("network", ipaddress.ip_network(value, strict=True))
        except ValueError:
            raise ValueError("Invalid scope CIDR; use a canonical network address") from None
    if value.startswith("*."):
        suffix = host(value[2:])
        try:
            ipaddress.ip_address(suffix)
        except ValueError:
            return ("suffix", suffix)
        raise ValueError("Wildcard IP scope is not supported")
    if "://" in value:
        raise ValueError("Scope rules must be hosts/IPs/CIDRs, not URL paths")
    return ("host", host(value))


def matches(candidate, rules):
    for kind, value in rules:
        if kind == "host" and candidate == value:
            return True
        if kind == "suffix" and candidate.endswith("." + value):
            return True
        if kind == "network":
            try:
                address = ipaddress.ip_address(candidate)
                if address.version == value.version and address in value:
                    return True
            except ValueError:
                pass
    return False


def lines(path):
    path = Path(path)
    if not path.is_file():
        raise ValueError("Expected an existing regular input file")
    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT + 1)
    if len(data) > MAX_INPUT:
        raise ValueError("Input limit is 4 MiB per file")
    return [s.strip() for s in data.decode("utf-8").splitlines() if s.strip() and not s.lstrip().startswith("#")]


def filter_candidates(candidates, allowed, excluded):
    if not allowed:
        raise ValueError("Scope must contain at least one rule")
    output = []
    seen = set()
    rejected = 0
    for value in candidates:
        normalized = host(value)
        if not matches(normalized, allowed) or matches(normalized, excluded):
            rejected += 1
        elif value not in seen:
            # Keep URL scheme/path/port: scope only checks its hostname, never rewrites a target.
            output.append(value)
            seen.add(value)
    return output, rejected


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", required=True)
    parser.add_argument("--exclude")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True, help="New file; existing files are never overwritten")
    args = parser.parse_args(argv)
    try:
        allowed = [rule(v) for v in lines(args.scope)]
        excluded = [rule(v) for v in lines(args.exclude)] if args.exclude else []
        candidates = lines(args.input)
        output, rejected = filter_candidates(candidates, allowed, excluded)
        # Parse and validate everything before opening the destination.
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            for value in output:
                stream.write(value + "\n")
        print(json.dumps({
            "candidates": len(candidates), "selected": len(output), "excluded": rejected,
            "output": args.output, "dns_resolution": False,
            "note": "Hostname filter only; check URL paths, ports, redirects and resolved IPs separately.",
        }))
    except (OSError, ValueError, UnicodeError) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
