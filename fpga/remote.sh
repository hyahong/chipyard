#!/usr/bin/env bash
set -euo pipefail

ROOT=/work/yahong/chipyard
FPGA=$ROOT/fpga

REMOTE="${REMOTE:-fpga-builder}"
REMOTE_ROOT=/work/yahong/chipyard

SUB_PROJECT=vcu118

#CONFIG=BoomVCU118Config
unset USE_CHISEL7
CONFIG="${CONFIG:-BoomVCU118PCIeConfig}"

JOBS=${JOBS:-32}

echo "REMOTE=$REMOTE, JOBS=$JOBS"

cd "$FPGA"

echo "== [1/6] Generate Verilog =="
make -j"$JOBS" \
  SUB_PROJECT="$SUB_PROJECT" \
  CONFIG="$CONFIG" \
  verilog

echo "== [2/6] Resolve build paths =="

BUILD_DIR=$(make -s \
  SUB_PROJECT="$SUB_PROJECT" \
  CONFIG="$CONFIG" \
  --eval='print-build-dir: ; @echo $(build_dir)' \
  print-build-dir)

SYNTH_LIST=$(make -s \
  SUB_PROJECT="$SUB_PROJECT" \
  CONFIG="$CONFIG" \
  --eval='print-synth-list: ; @echo $(synth_list_f)' \
  print-synth-list)

echo "BUILD_DIR=$BUILD_DIR"
echo "SYNTH_LIST=$SYNTH_LIST"

echo "== [3/6] Generate Vivado source manifest =="

make \
  SUB_PROJECT="$SUB_PROJECT" \
  CONFIG="$CONFIG" \
  "$SYNTH_LIST"

TMP_LIST=$(mktemp)
trap 'rm -f "$TMP_LIST"' EXIT

awk -v root="$ROOT/" '
  index($0, root) == 1 {
    print substr($0, length(root) + 1)
  }
' "$SYNTH_LIST" > "$TMP_LIST"

echo "RTL files: $(wc -l < "$TMP_LIST")"

echo "== [4/6] Copy files to fpga0 =="

ssh "$REMOTE" "
  mkdir -p \
    '$BUILD_DIR' \
    '$REMOTE_ROOT/fpga/fpga-shells/xilinx/common' \
    '$REMOTE_ROOT/fpga/fpga-shells/xilinx/vcu118'
"

rsync -a \
  --files-from="$TMP_LIST" \
  "$ROOT/" \
  "$REMOTE:$REMOTE_ROOT/"

rsync -a \
  --exclude='/obj/' \
  --exclude='/.Xil/' \
  --exclude='.rsync-partial/' \
  --exclude='/build-vivado.log' \
  --exclude='/vivado*.log' \
  --exclude='/vivado*.jou' \
  --exclude='/vivado*.str' \
  "$BUILD_DIR/" \
  "$REMOTE:$BUILD_DIR/"

rsync -a \
  "$ROOT/fpga/fpga-shells/xilinx/common/" \
  "$REMOTE:$REMOTE_ROOT/fpga/fpga-shells/xilinx/common/"

rsync -a \
  "$ROOT/fpga/fpga-shells/xilinx/vcu118/" \
  "$REMOTE:$REMOTE_ROOT/fpga/fpga-shells/xilinx/vcu118/"

echo "== [5/6] Check remote files =="

ssh "$REMOTE" "
  missing=0

  while IFS= read -r f; do
    [ -z \"\$f\" ] && continue

    if [ ! -e \"\$f\" ]; then
      echo \"MISSING: \$f\"
      missing=\$((missing + 1))
    fi
  done < '$SYNTH_LIST'

  echo \"missing=\$missing\"

  test \"\$missing\" -eq 0
  test -f '$REMOTE_ROOT/fpga/fpga-shells/xilinx/common/tcl/vivado.tcl'
  test -f '$REMOTE_ROOT/fpga/fpga-shells/xilinx/vcu118/tcl/board.tcl'
"

echo "== [6/6] Run Vivado on $REMOTE =="

SECONDS=0
BUILD_RC=0

ssh "$REMOTE" bash -s <<EOF || BUILD_RC=$?
set -euo pipefail

ROOT="$REMOTE_ROOT"
BD="$BUILD_DIR"
VSRC="$SYNTH_LIST"

IP_TCLS=\$(find "\$BD" -maxdepth 1 -type f -name '*.vivado.tcl' -print | sort | tr '\n' ' ')

echo "BD=\$BD"
echo "VSRC=\$VSRC"
echo "IP_TCLS=\$IP_TCLS"

cd "\$BD"

vivado \
  -nojournal \
  -mode batch \
  -source "\$ROOT/fpga/fpga-shells/xilinx/common/tcl/vivado.tcl" \
  -tclargs \
    -top-module VCU118FPGATestHarness \
    -F "\$VSRC" \
    -board vcu118 \
    -ip-vivado-tcls "\$IP_TCLS" \
  2>&1 | tee build-vivado.log
EOF

printf 'Total elapsed time for build: %02d:%02d\n' \
    "$((SECONDS / 60))" \
    "$((SECONDS % 60))"

echo "Remote build/SSH exit status: $BUILD_RC"
echo "== Collect all build files =="

if rsync -az \
    --partial-dir=.rsync-partial \
    --info=progress2,name1 \
		--stats \
    "$REMOTE:$BUILD_DIR/" \
    "$BUILD_DIR/"; then
  echo "Collection complete: $BUILD_DIR"
else
  COLLECT_RC=$?
  echo "ERROR: Collection failed (rsync=$COLLECT_RC)." >&2
  echo "Keeping $REMOTE running." >&2
  exit "$COLLECT_RC"
fi

exit "$BUILD_RC"
