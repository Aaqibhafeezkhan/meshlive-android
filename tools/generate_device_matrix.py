"""Generate high-rate scenarios only from exported real Settings capabilities."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path

from benchmark_layout import output_path, resolve

from benchmark_case_state import request_key


def generate_cases(source, receiver, sender_caps, receiver_caps, duration=90):
    if source == receiver:
        raise ValueError("Source and receiver must be different phones")
    decoders = set(receiver_caps["decoders"])
    cases = {}
    for encoder in sender_caps["encoders"]:
        if encoder["codec"] not in decoders:
            continue
        for mode in encoder["modes"]:
            for transport, maximum in sender_caps["transport_max_kbps"].items():
                if mode["bitrate_kbps"] > maximum:
                    continue
                combinations = itertools.product(mode["fps"], encoder["rate_controls"],
                                                  receiver_caps["playback"], (False, True))
                for fps, rate_control, playback, thermal in combinations:
                    case = {"source": source, "transport": transport,
                                  "duration_s": duration,
                                  "capability_basis": {"sender_fingerprint": sender_caps["fingerprint"],
                                                       "receiver_fingerprint": receiver_caps["fingerprint"],
                                                       "camera_lens": sender_caps["camera_lens"]},
                                  "profile": {"codec": encoder["name"], "width": mode["width"],
                                              "height": mode["height"], "fps": fps,
                                              "bitrate_kbps": mode["bitrate_kbps"], "playback": playback,
                                              "rate_control": rate_control, "thermal_reduction": thermal},
                                  "receivers": [{"phone": receiver, "parent": source}]}
                    case["id"] = f"{source}-{receiver}-" + request_key(case)[:24]
                    cases[case["id"]] = case
    return list(cases.values())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--receiver", required=True)
    parser.add_argument("--sender-capabilities", type=Path, required=True)
    parser.add_argument("--receiver-capabilities", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "catalog")
    args.sender_capabilities = resolve(args.sender_capabilities)
    args.receiver_capabilities = resolve(args.receiver_capabilities)
    if args.output.exists():
        raise FileExistsError(args.output)
    sender_caps = json.loads(args.sender_capabilities.read_text(encoding="utf-8"))
    receiver_caps = json.loads(args.receiver_capabilities.read_text(encoding="utf-8"))
    cases = generate_cases(args.source, args.receiver, sender_caps, receiver_caps)
    if not cases:
        parser.error("No compatible scenarios; do not substitute another encoder or target")
    basis = {phone: {"path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                     "fingerprint": caps["fingerprint"], "node_prefix": caps.get("node_prefix")}
             for phone, path, caps in ((args.source, args.sender_capabilities, sender_caps),
                                      (args.receiver, args.receiver_capabilities, receiver_caps))}
    for case in cases:
        case["capability_basis"].update(basis)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as destination:
        destination.write(json.dumps({"schema_version": 1, "cases": cases}, indent=2) + "\n")
    print(f"{len(cases)} compatible high-rate cases -> {args.output}")
    print("Plan only: generation is not physical coverage. Thermal-on requires actual heat to exercise reduction.")


if __name__ == "__main__":
    main()
