#!/usr/bin/env bash
# Generates the corpus and runs every OCR configuration (M2 slice 2a). Results: benchmarks/results/ocr (git-ignored).
# Usage (from the repository root): bash benchmarks/ocr/run_all.sh [config-name ...]
set -euo pipefail

IMAGE=mimorag-ocr-bench:2a
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite container paths such as /data
mkdir -p benchmarks/results/ocr
RESULTS="$(cd benchmarks/results/ocr && (pwd -W 2>/dev/null || pwd))"
# Sidecar-like limits: no network, 4 CPUs, 3 GB memory and no swap (so memory numbers are honest).
RUN=(docker run --rm --network none --cpus 4 --memory 3g --memory-swap 3g -v "$RESULTS:/data" "$IMAGE")

if [ ! -f "$RESULTS/corpus/manifest.json" ]; then
  "${RUN[@]}" python generate_corpus.py /data/corpus
fi

# Defaults for every run: PP-OCRv5 mobile detection, Arabic PP-OCRv5 mobile recognition, orientation off, 4 threads.
BASE="--orientation off"
declare -A CONFIGS=(
  [pd_300_d1600]="$BASE --dpi 300 --det-max-side 1600"
  [pd_300_d1200]="$BASE --dpi 300 --det-max-side 1200"
  [pd_300_d2400]="$BASE --dpi 300 --det-max-side 2400"
  [pd_300_dfull]="$BASE --dpi 300"
  [pd_200_d1600]="$BASE --dpi 200 --det-max-side 1600"
  [pd_300_d1600_orient]="--orientation on --dpi 300 --det-max-side 1600"
  [pd_300_d1600_serverdet]="$BASE --dpi 300 --det-max-side 1600 --det PP-OCRv5_server_det"
  [pd_300_d1600_t2]="$BASE --dpi 300 --det-max-side 1600 --threads 2"
  [pd_300_d1600_en]="$BASE --dpi 300 --det-max-side 1600 --rec en_PP-OCRv5_mobile_rec --scripts en,mixed"
  [onnx_300_d1600]="$BASE --dpi 300 --det-max-side 1600 --engine onnxruntime"
)
ORDER=(pd_300_d1600 pd_300_d1200 pd_300_d2400 pd_300_dfull pd_200_d1600 pd_300_d1600_orient pd_300_d1600_serverdet
       pd_300_d1600_t2 pd_300_d1600_en onnx_300_d1600)

NAMES=("$@")
[ ${#NAMES[@]} -eq 0 ] && NAMES=("${ORDER[@]}")

for name in "${NAMES[@]}"; do
  echo "=== $name"
  # shellcheck disable=SC2086
  # Upside-down pages are measured in their own pass below (runs_flip), as in the published report.
  if ! "${RUN[@]}" python run_benchmark.py --corpus /data/corpus --out /data/runs --name "$name" ${CONFIGS[$name]} \
      --profiles vector,clean300,noisy200,lowres150,rotated300; then
    echo "!!! $name failed (exit $?): see output above (137 = killed by the 3 GB memory cap)"
  fi
done

# Everything below runs only for a full run (no configuration names given).
if [ $# -gt 0 ]; then exit 0; fi
echo "=== upside-down pages, orientation off/on"
for o in off on; do
  "${RUN[@]}" python run_benchmark.py --corpus /data/corpus --out /data/runs_flip --name "flip180_orient_$o" \
    --orientation "$o" --dpi 300 --det-max-side 1600 --profiles flip180
done
echo "=== tesseract"
for psm in 3 6; do
  "${RUN[@]}" python run_tesseract.py --corpus /data/corpus --out /data/runs_tess --name "tess_300_psm$psm" --psm "$psm"
done
echo "=== mitigations for Latin/digit tokens inside Arabic lines"
"${RUN[@]}" python dual_rec.py --corpus /data/corpus --out /data/runs_dual
"${RUN[@]}" python hybrid_rec.py --corpus /data/corpus --out /data/runs_hybrid --agree 0.7
"${RUN[@]}" python hybrid_rec.py --corpus /data/corpus --out /data/runs_hybrid --agree 0.5
# The preferred candidate (gated hybrid); reports line replacement and token insertion side by side.
"${RUN[@]}" python hybrid_rec.py --corpus /data/corpus --out /data/runs_hybrid --agree 0.5 --gate en
"${RUN[@]}" python flag_report.py /data/corpus /data/runs_hybrid/hybrid_agree50_gateen.jsonl
echo "=== summaries"
"${RUN[@]}" sh -c "python summarize.py /data/runs > /data/summary.md && python summarize.py /data/runs_flip > /data/summary_flip.md"
