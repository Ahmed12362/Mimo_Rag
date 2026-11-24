"""Per-page CER table for selected configurations. Usage: python per_page.py /data/runs cfg1 cfg2 ..."""
import json, sys
from collections import defaultdict
from pathlib import Path

runs, configs = Path(sys.argv[1]), sys.argv[2:]
table = defaultdict(dict)
for cfg in configs:
    for line in (runs / f"{cfg}.jsonl").read_text(encoding="utf-8").splitlines()[1:]:
        r = json.loads(line)
        table[(r["file"].split("/")[1].split("__")[0].replace(".pdf", ""), r["profile"])][cfg] = r["raw"]["cer"]
print("| Page · profile | " + " | ".join(configs) + " |")
print("|---|" + "---|" * len(configs))
for key in sorted(table):
    print(f"| {key[0]} · {key[1]} | " + " | ".join(f"{table[key].get(c, float('nan')) * 100:.1f}%" for c in configs) + " |")
