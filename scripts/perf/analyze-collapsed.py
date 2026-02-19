#!/usr/bin/env python3
from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: analyze-collapsed.py <collapsed-file>")
        return 2

    path = Path(sys.argv[1])
    if not path.exists():
        print(f"File not found: {path}")
        return 2

    total = 0
    top_frame = defaultdict(int)
    top_service_frame = defaultdict(int)
    top_core_frame = defaultdict(int)
    top_tink_frame = defaultdict(int)

    with path.open("r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                stack, value = line.rsplit(" ", 1)
                samples = int(value)
            except ValueError:
                continue
            frames = stack.split(";")
            if not frames:
                continue

            total += samples
            leaf = frames[-1]
            top_frame[leaf] += samples

            for frame in reversed(frames):
                if "no/ssb/dlp/pseudo/service" in frame:
                    top_service_frame[frame] += samples
                    break
            for frame in reversed(frames):
                if "no/ssb/dlp/pseudo/core" in frame:
                    top_core_frame[frame] += samples
                    break
            for frame in reversed(frames):
                if "com/google/crypto/tink" in frame or "no/ssb/crypto/tink" in frame:
                    top_tink_frame[frame] += samples
                    break

    if total == 0:
        print("No samples found")
        return 1

    def print_top(title: str, data: dict[str, int], limit: int = 12) -> None:
        print(f"\n{title}")
        for frame, count in sorted(data.items(), key=lambda x: x[1], reverse=True)[:limit]:
            pct = 100.0 * count / total
            print(f"  {pct:6.2f}%  {frame}")

    print(f"Total samples: {total}")
    print_top("Top leaf frames", top_frame)
    print_top("Top service frames", top_service_frame)
    print_top("Top core frames", top_core_frame)
    print_top("Top tink frames", top_tink_frame)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
