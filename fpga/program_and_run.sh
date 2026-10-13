#!/usr/bin/env bash
set -euo pipefail

RUN_ID=hnsw-2

SRC_BASE=/work/yahong

SRC_FPGA_BASE="$SRC_BASE/chipyard/fpga/generated-src/chipyard.fpga.vcu118.VCU118FPGATestHarness.BoomVCU118PCIe4GiBConfig"
SRC_BINARY_BASE="$SRC_BASE/riscv-dev/binary"
SRC_TOOL_BASE="$SRC_BASE/riscv-dev/tool"

SRC_BITSTREAM="$SRC_FPGA_BASE/obj/VCU118FPGATestHarness.bit"
SRC_BINARY="$SRC_BINARY_BASE/build/hnsw.bin"

DST_BASE=/work/yahong

DST_FPGA_BASE="$DST_BASE/fpga"
DST_BINARY_BASE="$DST_BASE/fpga/binary"
DST_TOOL_BASE="$DST_BASE/fpga/tool"
DST_OUTPUT_BASE="$DST_BASE/fpga/output"

DST_BITSTREAM="$DST_FPGA_BASE/VCU118FPGATestHarness.bit"
DST_BINARY="$DST_BINARY_BASE/hnsw.bin"

# clean up
rm -rf "$SRC_BINARY_BASE/build"
ssh fpga0 "rm -rf $DST_FPGA_BASE/*; mkdir -p $DST_BINARY_BASE; mkdir -p $DST_TOOL_BASE; mkdir -p $DST_OUTPUT_BASE"

make -C "$SRC_BINARY_BASE"

# check
ls -alh $SRC_BITSTREAM $SRC_BINARY $SRC_TOOL_BASE/vcu118_program.tcl $SRC_TOOL_BASE/vcu118_pcie_uart.py

scp $SRC_BITSTREAM \
  fpga0:$DST_BITSTREAM

scp $SRC_BINARY \
  fpga0:$DST_BINARY

scp $SRC_TOOL_BASE/vcu118_program.tcl $SRC_TOOL_BASE/vcu118_pcie_uart.py \
  fpga0:$DST_TOOL_BASE

ssh fpga0 "ls -alh $DST_BITSTREAM $DST_BINARY $DST_TOOL_BASE"

# remove and re-install
printf -v FPGA_REMOTE_CMD 'DST_OUTPUT_BASE=%q; DST_TOOL_BASE=%q; DST_BITSTREAM=%q; DST_BINARY=%q; RUN_ID=%q\n' \
  "$DST_OUTPUT_BASE" "$DST_TOOL_BASE" "$DST_BITSTREAM" "$DST_BINARY" "$RUN_ID"

FPGA_REMOTE_CMD+='
set -e

sudo python3 "$DST_TOOL_BASE/vcu118_pcie_uart.py" \
  --image "$DST_BINARY" \
  --hnsw /work/yahong/hnsw_data/boom_hnsw.bin \
  --prefix /dev/xdma0 \
  --bdf 0000:01:00.0 \
  --uart /dev/serial/by-id/usb-Silicon_Labs_CP2105_Dual_USB_to_UART_Bridge_Controller_007F6F92-if01-port0 \
  --timeout 0 \
  --output-dir output/uart \
	2>&1 | tee "$DST_OUTPUT_BASE/vcu118_uart.log"

echo "Test exit status: $?"
'

ssh -t fpga0 "$FPGA_REMOTE_CMD"

