.section .text.init, "ax", @progbits
.option norvc
.option norelax
.option nopic

.globl _start
_start:
    csrci mstatus, 8
    csrw  mie, zero

    csrr  a0, mhartid
    bnez  a0, .Lpark

    la    a1, _dtb

    fence rw, rw
    fence.i

    /* form the positive RV64 address 0x0000000080000000. */
    li    t0, 1
    slli  t0, t0, 31
    jr    t0

.Lpark:
    wfi
    j     .Lpark

/* the raw ROM is exactly 128 bytes. the appended DTB starts here. */
.org 0x80, 0
.globl _dtb
_dtb:
