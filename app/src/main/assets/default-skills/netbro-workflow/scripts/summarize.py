#!/usr/bin/env python3
"""Summarize saved BBOT/Nmap/Nuclei/Legba evidence without running scanners."""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit, urlunsplit
import xml.etree.ElementTree as ET

MAX_BYTES = 32 * 1024 * 1024
MAX_LINE = 1024 * 1024
MAX_RECORDS = 100000


def short(value, limit=240):
    return str(value).replace("\x00", "")[:limit]


def identifier(value):
    if not isinstance(value, str) or not value or len(value) > 512:
        raise ValueError("Expected a nonempty category identifier of at most 512 characters")
    return value


def address_preview(value):
    """Drop URL credentials/query/fragment; do not include opaque result data."""
    if not isinstance(value, str):
        return ""
    if "://" not in value:
        return short(value.split("?", 1)[0].split("#", 1)[0].rsplit("@", 1)[-1])
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        if ":" in host:
            host = "[" + host + "]"
        if parsed.port is not None:
            host += ":" + str(parsed.port)
        return short(urlunsplit((parsed.scheme, host, parsed.path, "", "")))
    except ValueError:
        return "[invalid address]"


def json_records(path, max_bytes):
    consumed = 0
    records = 0
    with path.open("rb") as stream:
        line_number = 0
        while True:
            raw = stream.readline(MAX_LINE + 1)
            if not raw:
                break
            line_number += 1
            consumed += len(raw)
            if len(raw) > MAX_LINE or consumed > max_bytes:
                raise ValueError("JSONL byte/line limit exceeded; select a smaller saved segment")
            if not raw.strip():
                continue
            try:
                item = json.loads(raw)
            except (ValueError, UnicodeError):
                raise ValueError("Invalid JSONL at line " + str(line_number)) from None
            if not isinstance(item, dict):
                raise ValueError("Expected a JSON object at line " + str(line_number))
            records += 1
            if records > MAX_RECORDS:
                raise ValueError("Record limit exceeded; select a smaller saved segment")
            yield item


def summarize_json(kind, path, limit, max_bytes):
    counts = Counter()
    states = Counter()
    samples = []
    total = 0
    for item in json_records(path, max_bytes):
        total += 1
        if kind == "bbot":
            category = item.get("type")
            if not isinstance(category, str) or not category:
                raise ValueError("BBOT record has no event type")
            counts[identifier(category)] += 1
            distance = item.get("scope_distance")
            states["in_scope" if type(distance) is int and distance == 0 else "other_or_unknown"] += 1
            data = item.get("data_json", item.get("data"))
            preview = {"type": short(category, 100), "scope_distance": distance if type(distance) is int else None}
            if category in {"DNS_NAME", "IP_ADDRESS", "IP_RANGE", "URL", "URL_UNVERIFIED", "OPEN_TCP_PORT"} and isinstance(data, str):
                preview["address"] = address_preview(data)
        elif kind == "nuclei":
            template = item.get("template-id")
            if not isinstance(template, str) or not template:
                raise ValueError("Nuclei record has no template-id")
            info = item.get("info")
            severity = info.get("severity", "unknown") if isinstance(info, dict) else "unknown"
            counts[identifier(template)] += 1
            severity = severity if severity in ("info", "low", "medium", "high", "critical", "unknown") else "unknown"
            states[severity] += 1
            preview = {
                "template_id": short(template, 100), "severity": short(severity, 30),
                "endpoint": address_preview(item.get("matched-at", item.get("host", ""))),
            }
        else:
            plugin = item.get("plugin")
            target = item.get("target")
            if not isinstance(plugin, str) or not isinstance(target, str):
                raise ValueError("Legba record requires plugin and target")
            counts[identifier(plugin)] += 1
            partial = item.get("partial")
            state = "partial" if partial is True else "full_match" if partial is False else "unknown"
            states[state] += 1
            preview = {"plugin": short(plugin, 100), "target": address_preview(target), "match": state}
            # Never read or copy credential-bearing data/session fields into the report.
        if len(samples) < limit:
            samples.append(preview)
    return {
        "records": total, "counts": dict(sorted(counts.most_common(100))),
        "distinct_categories": len(counts), "counts_truncated": len(counts) > 100,
        "states": dict(sorted(states.items())), "preview": samples,
        "preview_truncated": total > len(samples), "scan_completion": "not_recorded_in_jsonl",
    }


def summarize_nmap(path, limit, max_bytes):
    with path.open("rb") as stream:
        data = stream.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError("XML byte limit exceeded")
    try:
        xml_text = data.decode("utf-8-sig")
    except UnicodeError:
        raise ValueError("Expected UTF-8 Nmap XML") from None
    if "<!ENTITY" in xml_text.upper():
        raise ValueError("XML entity declarations are not accepted")
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        raise ValueError("Invalid or incomplete Nmap XML") from None
    if root.tag != "nmaprun":
        raise ValueError("Expected a nmaprun XML document")
    hosts = Counter()
    ports = Counter()
    preview = []
    port_records = 0
    for host in root.findall("host"):
        status = host.find("status")
        hosts[status.get("state", "unknown") if status is not None else "unknown"] += 1
        address = next((a.get("addr", "") for a in host.findall("address") if a.get("addrtype") in {"ipv4", "ipv6"}), "")
        for extra in host.findall("ports/extraports"):
            try:
                count = int(extra.get("count", "0"))
            except ValueError:
                raise ValueError("Invalid Nmap extraports count") from None
            if count < 0:
                raise ValueError("Negative Nmap extraports count")
            ports[short(extra.get("state", "unknown"), 40)] += count
        for port in host.findall("ports/port"):
            port_records += 1
            state = port.find("state")
            state_name = state.get("state", "unknown") if state is not None else "unknown"
            ports[short(state_name, 40)] += 1
            if len(preview) < limit:
                service = port.find("service")
                preview.append({
                    "host": short(address), "protocol": short(port.get("protocol", ""), 20),
                    "port": short(port.get("portid", ""), 10), "state": short(state_name, 40),
                    "service": short(service.get("name", "")) if service is not None else "",
                    "product": short(service.get("product", "")) if service is not None else "",
                    "version": short(service.get("version", "")) if service is not None else "",
                })
    finished = root.find("runstats/finished")
    completion = finished.get("exit", "unknown") if finished is not None else "not_recorded"
    return {
        "hosts": dict(sorted(hosts.items())), "port_states": dict(sorted(ports.items())),
        "port_records": port_records, "preview": preview,
        "preview_truncated": port_records > len(preview),
        "scan_completion": short(completion, 40),
        "note": "Also check the producer exit code and requested scan coverage.",
    }


def summarize(kind, path, limit=20, max_bytes=MAX_BYTES):
    path = Path(path)
    if not path.is_file():
        raise ValueError("Input must be an existing regular file")
    if path.stat().st_size > max_bytes:
        raise ValueError("Input byte limit exceeded")
    details = summarize_nmap(path, limit, max_bytes) if kind == "nmap" else summarize_json(kind, path, limit, max_bytes)
    return {"schema_version": 1, "tool": kind, "source": str(path), **details}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool", choices=["bbot", "nmap", "nuclei", "legba"])
    parser.add_argument("file")
    parser.add_argument("--limit", type=int, default=20, help="Preview records, 0-200 (counts include all records)")
    parser.add_argument("--max-bytes", type=int, default=MAX_BYTES, help="Input cap, at most 64 MiB")
    args = parser.parse_args(argv)
    if not 0 <= args.limit <= 200 or not 1 <= args.max_bytes <= 64 * 1024 * 1024:
        parser.error("Invalid preview or input limit")
    try:
        result = summarize(args.tool, args.file, args.limit, args.max_bytes)
    except (OSError, ValueError) as error:
        print(json.dumps({"error": str(error), "tool": args.tool}), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
