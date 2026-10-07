ROOT_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BUILD_DIR := $(ROOT_DIR)build
CROSS := $(RISCV)/bin/riscv64-unknown-elf-

.PHONY: all bin dump
all: bin dump

bin: $(BUILD_DIR)/pcieboot.bin
dump: $(BUILD_DIR)/pcieboot.dump

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/pcieboot.elf: $(ROOT_DIR)head.S $(ROOT_DIR)Makefile | $(BUILD_DIR)
	$(CROSS)gcc \
		-march=rv64ima_zicsr_zifencei -mabi=lp64 \
		-mcmodel=medany -fno-pic -fno-pie \
		-nostdlib -nostartfiles -static \
		-Wl,-Ttext=0x10000,-e,_start,--build-id=none,--no-relax \
		-o $@ $<

$(BUILD_DIR)/pcieboot.bin: $(BUILD_DIR)/pcieboot.elf
	$(CROSS)objcopy -O binary -j .text $< $@
	test "$$(wc -c < $@)" -eq 128

$(BUILD_DIR)/pcieboot.dump: $(BUILD_DIR)/pcieboot.elf
	$(CROSS)objdump -d $< > $@
