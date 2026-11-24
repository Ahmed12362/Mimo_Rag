"""Review-flag precision and missing-token coverage for a hybrid run (insertion variant).

A flag counts as useful when it matches a ground-truth Latin/digit token exactly, as a substring, or within 34%
normalised edit distance (a misread of a real token still points the reviewer at it).
Usage: python flag_report.py /data/corpus /data/runs_hybrid/hybrid_agree50_gateen.jsonl
"""
import json
import sys

from rapidfuzz.distance import Levenshtein

from hybrid_rec import tokens
from run_benchmark import normalise


def similar(a, b):
    return a in b or b in a or Levenshtein.normalized_distance(a, b) <= 0.34


def main(corpus, run_file):
    manifest = {m["file"]: m for m in json.load(open(f"{corpus}/manifest.json", encoding="utf-8"))}
    tiers = {"confirmed": [0, 0], "possible": [0, 0]}
    missing_total = missing_flagged = 0
    for line in open(run_file, encoding="utf-8").read().splitlines()[1:]:
        record = json.loads(line)
        truth = set().union(*(tokens(normalise(l)) for l in manifest[record["file"]]["lines"]))
        output = set().union(set(), *(tokens(r) for r in record["merged"]["rows"]))
        for flag in record["reviewFlags"]:
            tier = "confirmed" if flag in record["unplacedTokens"] else "possible"
            tiers[tier][0 if any(similar(flag, t) for t in truth) else 1] += 1
        for token in truth - output:
            missing_total += 1
            missing_flagged += any(similar(token, f) for f in record["reviewFlags"])
    for tier, (useful, junk) in tiers.items():
        print(f"{tier:<10} useful {useful:>3}  junk {junk:>3}  precision {useful / max(1, useful + junk):.2f}")
    print(f"ground-truth tokens still missing after merge: {missing_total}, pointed to by a flag: {missing_flagged}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
