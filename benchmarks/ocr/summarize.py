"""Summarises benchmark runs into Markdown tables. Usage: python summarize.py benchmarks/results/ocr/runs"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def pct(v):
    return f"{v * 100:.1f}%"


def p95(values):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(0.95 * (len(values) - 1))))]


def main(runs_dir):
    configs = []
    for path in sorted(Path(runs_dir).glob("*.jsonl")):
        lines = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines()]
        configs.append((lines[0]["summary"], lines[1:]))

    print("## Configurations\n")
    print("| Config | Detection | Recognition | DPI | Orientation | Model load | Peak RSS | Pages |")
    print("|---|---|---|---|---|---|---|---|")
    for s, _ in configs:
        print(f"| {s['config']} | {s['det']} | {s['rec']} | {s['dpi']} | {s['orientation']} | "
              f"{s['modelLoadSeconds']} s | {s['peakRssMb']} MB | {s['pages']} |")

    for variant in ("raw", "v2l"):
        print(f"\n## Accuracy by script and scan profile — variant `{variant}`\n")
        print("CER / CER without diacritics / order-free line CER / reading order\n")
        groups = sorted({(r["script"], r["profile"]) for _, recs in configs for r in recs})
        print("| Script · profile | " + " | ".join(s["config"] for s, _ in configs) + " |")
        print("|---|" + "---|" * len(configs))
        for script, profile in groups:
            cells = []
            for _, recs in configs:
                sel = [r[variant] for r in recs if r["script"] == script and r["profile"] == profile]
                if not sel:
                    cells.append("—")
                    continue
                m = lambda k: statistics.mean(x[k] for x in sel)
                cells.append(f"{pct(m('cer'))} / {pct(m('cerNoDiacritics'))} / {pct(m('lineCer'))} / "
                             f"{m('readingOrder'):.2f}")
            print(f"| {script} · {profile} | " + " | ".join(cells) + " |")

    print("\n## OCR latency and CPU per page: wall mean / p95 (ms), CPU mean (ms), average cores busy\n")
    print("| Config | Wall all pages | Wall scanned only | CPU per page | Cores busy | Render (mean) |")
    print("|---|---|---|---|---|---|")
    for s, recs in configs:
        allv = [r["ocrMs"] for r in recs]
        scan = [r["ocrMs"] for r in recs if r["profile"] != "vector"]
        cpu = [r["ocrCpuMs"] for r in recs]
        print(f"| {s['config']} | {statistics.mean(allv):.0f} / {p95(allv)} | "
              f"{statistics.mean(scan):.0f} / {p95(scan)} | {statistics.mean(cpu):.0f} | "
              f"{sum(cpu) / max(1, sum(allv)):.1f} | {statistics.mean(r['renderMs'] for r in recs):.0f} |")

    print("\n## Worst pages (raw CER)\n")
    worst = sorted(((r["raw"]["cer"], s["config"], r["file"], r["sample"]) for s, recs in configs for r in recs),
                   reverse=True)[:8]
    for c, cfg, f, sample in worst:
        print(f"- {pct(c)} · {cfg} · {f} · first rows: {sample}")


if __name__ == "__main__":
    main(sys.argv[1])
