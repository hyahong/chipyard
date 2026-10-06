package chipyard.fpga.vcu118

import chisel3._

import freechips.rocketchip.diplomacy.{LazyModule, LazyRawModuleImp, BundleBridgeSource}
import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.subsystem.{SystemBusKey}
import freechips.rocketchip.prci._
import freechips.rocketchip.util.{AsyncResetSynchronizerShiftReg, ResetCatchAndSync}
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.ip.xilinx.{IBUF, IBUFDS_GTE4, PowerOnResetFPGAOnly}
import sifive.fpgashells.devices.xilinx.xdma.XDMAEndpointDDRBridge
import sifive.fpgashells.shell._
import sifive.fpgashells.clocks._

import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTPortIO}
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIPortIO}

import chipyard._
import chipyard.harness._

class VCU118PCIeIO extends Bundle {
  val refclk = Input(new LVDSClock)
  val perst_n = Input(Bool())
  val pci_exp_txp = Output(UInt(8.W))
  val pci_exp_txn = Output(UInt(8.W))
  val pci_exp_rxp = Input(UInt(8.W))
  val pci_exp_rxn = Input(UInt(8.W))
}

class VCU118FPGATestHarness(override implicit val p: Parameters) extends VCU118ShellBasicOverlays {

  def dp = designParameters
  val pcieEnabled = dp(VCU118PCIeKey)
  val dualDDR = dp(VCU118DualDDRKey)

  val pmod_is_sdio  = p(VCU118ShellPMOD) == "SDIO"
  val jtag_location = Some(if (pmod_is_sdio) "FMC_J2" else "PMOD_J52")

  // Order matters; ddr depends on sys_clock
  val uart      = Overlay(UARTOverlayKey, new UARTVCU118ShellPlacer(this, UARTShellInput()))
  val sdio      = if (pmod_is_sdio) Some(Overlay(SPIOverlayKey, new SDIOVCU118ShellPlacer(this, SPIShellInput()))) else None
  val jtag      = Overlay(JTAGDebugOverlayKey, new JTAGDebugVCU118ShellPlacer(this, JTAGDebugShellInput(location = jtag_location)))
  val cjtag     = Overlay(cJTAGDebugOverlayKey, new cJTAGDebugVCU118ShellPlacer(this, cJTAGDebugShellInput()))
  val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanVCU118ShellPlacer(this, JTAGDebugBScanShellInput()))
  val fmc       = Overlay(PCIeOverlayKey, new PCIeVCU118FMCShellPlacer(this, PCIeShellInput()))
  val edge      = Overlay(PCIeOverlayKey, new PCIeVCU118EdgeShellPlacer(this, PCIeShellInput()))
  val sys_clock2 = Overlay(ClockInputOverlayKey, new SysClock2VCU118ShellPlacer(this, ClockInputShellInput()))
  val ddr2       = Overlay(DDROverlayKey, new DDR2VCU118ShellPlacer(this, DDRShellInput()))

// DOC include start: ClockOverlay
  // Place the primary reference clock independently of other clock overlays.
  require(dp(ClockInputOverlayKey).size >= 1)
  val sysClkNode = dp(ClockInputOverlayKey).find(_.name == "sys_clock").get
    .place(ClockInputDesignInput()).overlayOutput.node

  /*** Connect/Generate clocks ***/

  // connect to the PLL that will generate multiple clocks
  val harnessSysPLL = dp(PLLFactoryKey)()
  harnessSysPLL := sysClkNode

  // create and connect to the dutClock
  val dutFreqMHz = (dp(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toInt
  val dutClock = ClockSinkNode(freqMHz = dutFreqMHz)
  println(s"VCU118 FPGA Base Clock Freq: ${dutFreqMHz} MHz")
  val dutWrangler = LazyModule(new ResetWrangler)
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLL
// DOC include end: ClockOverlay

  // Keep the shared DDR frontend independent of both the CPU clock and PCIe reset.
  // PCIe's nominally 125 MHz AXI clock is a separate clock domain.
  val pcieMemoryClock = if (pcieEnabled) {
    val memoryClock = ClockSinkNode(freqMHz = 125)
    val memoryGroup = ClockGroup()
    memoryClock := dutWrangler.node := memoryGroup := harnessSysPLL
    Some(memoryClock)
  } else None

  /*** UART ***/

// DOC include start: UartOverlay
  // 1st UART goes to the VCU118 dedicated UART

  val io_uart_bb = BundleBridgeSource(() => (new UARTPortIO(dp(PeripheryUARTKey).head)))
  dp(UARTOverlayKey).head.place(UARTDesignInput(io_uart_bb))
// DOC include end: UartOverlay

  /*** SPI ***/

  // 1st SPI goes to the VCU118 SDIO port

  val io_spi_bb = BundleBridgeSource(() => (new SPIPortIO(dp(PeripherySPIKey).head)))
  dp(SPIOverlayKey).head.place(SPIDesignInput(dp(PeripherySPIKey).head, io_spi_bb))

  /*** DDR ***/

  val memoryParams = dp(ExtTLMem).get.master
  val totalDDRSize = dp(VCU118DDRSize) +
    (if (dualDDR) dp(VCU118DDR2Size) else BigInt(0))
  require(memoryParams.size == totalDDRSize, "CPU memory size must match the placed DDR windows")

  // Select by name: adding DDR2 must not change which bank is DDR0.
  val ddrPlaced = dp(DDROverlayKey).find(_.name == "ddr").get
    .place(DDRDesignInput(memoryParams.base, dutWrangler.node, harnessSysPLL))
  val ddr2Placed = if (dualDDR) {
    val clock2 = dp(ClockInputOverlayKey).find(_.name == "sys_clock2").get
      .place(ClockInputDesignInput())
    // The clock overlay exports its buffered clock through this negotiated edge.
    val clock2Sink = ClockSinkNode(Seq(ClockSinkParameters()))
    clock2Sink := clock2.overlayOutput.node
    Some(dp(DDROverlayKey).find(_.name == "ddr2").get.place(DDRDesignInput(
      memoryParams.base + dp(VCU118DDRSize), dutWrangler.node, harnessSysPLL)))
  } else None
  val ddrPlacedOverlays = Seq(ddrPlaced) ++ ddr2Placed.toSeq
  val ddrNode = ddrPlaced.overlayOutput.ddr
  val pcieMIGs = if (pcieEnabled) {
    ddrPlacedOverlays.map {
      case placed: DDRVCU118PlacedOverlay => placed.mig
      case placed: DDR2VCU118PlacedOverlay => placed.mig
      case _ => throw new IllegalArgumentException("VCU118 PCIe requires a VCU118 MIG DDR overlay")
    }
  } else Seq.empty

  // memoryNode in the XDMA bridge is an identity node, not a fanout.
  // A downstream crossbar routes the single CPU/DMA memory path to both MIGs.
  val ddrXbar = if (dualDDR) Some(LazyModule(new TLXbar)) else None
  ddrXbar.foreach { xbar =>
    ddrPlacedOverlays.foreach { placed => placed.overlayOutput.ddr := xbar.node }
  }
  val ddrMemoryNode: TLInwardNode = ddrXbar match {
    case Some(xbar) => xbar.node
    case None => ddrNode
  }

  // connect 1 mem. channel to the FPGA DDR
  val ddrClient = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "chip_ddr",
    sourceId = IdRange(0, 1 << dp(ExtTLMem).get.master.idBits)
  )))))
  val pcieCpuSource = if (pcieEnabled) Some(LazyModule(new TLAsyncCrossingSource())) else None
  val pcieBridge = if (pcieEnabled) {
    val bridge = LazyModule(new XDMAEndpointDDRBridge(dp(ExtTLMem).get.master.beatBytes))
    pcieCpuSource.get.node := TLWidthWidget(dp(ExtTLMem).get.master.beatBytes) := ddrClient
    bridge.cpuSink.node := pcieCpuSource.get.node
    ddrMemoryNode := bridge.memoryNode
    Some(bridge)
  } else {
    ddrMemoryNode := TLWidthWidget(dp(ExtTLMem).get.master.beatBytes) := ddrClient
    None
  }

  /*** JTAG ***/
  val jtagPlacedOverlay = dp(JTAGDebugOverlayKey).head.place(JTAGDebugDesignInput())

  // module implementation
  override lazy val module = new VCU118FPGATestHarnessImp(this)
}

class VCU118FPGATestHarnessImp(_outer: VCU118FPGATestHarness) extends LazyRawModuleImp(_outer) with HasHarnessInstantiators {
  override def provideImplicitClockToLazyChildren = true
  val vcu118Outer = _outer

  val reset = IO(Input(Bool())).suggestName("reset")
  _outer.xdc.addPackagePin(reset, "L19")
  _outer.xdc.addIOStandard(reset, "LVCMOS12")

  val resetIBUF = Module(new IBUF)
  resetIBUF.io.I := reset

  val sysclk: Clock = _outer.sysClkNode.out.head._1.clock

  val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
  _outer.sdc.addAsyncPath(Seq(powerOnReset))

  val ereset: Bool = _outer.chiplink.get() match {
    case Some(x: ChipLinkVCU118PlacedOverlay) => !x.ereset_n
    case _ => false.B
  }

  _outer.pllReset := (resetIBUF.io.O || powerOnReset || ereset)

  // reset setup
  val hReset = Wire(Reset())
  hReset := _outer.dutClock.in.head._1.reset
  // Only the opt-in ResetPort binder uses this reset; infrastructure uses hReset.
  val chipReset = WireDefault(hReset.asBool)

  def referenceClockFreqMHz = _outer.dutFreqMHz
  def referenceClock = _outer.dutClock.in.head._1.clock
  def referenceReset = hReset
  def success = { require(false, "Unused"); false.B }

  childClock := referenceClock
  childReset := referenceReset

  if (_outer.pcieEnabled) {
    val bridge = _outer.pcieBridge.get
    val migs = _outer.pcieMIGs
    val memoryClock = _outer.pcieMemoryClock.get.in.head._1
    val pcie = IO(new VCU118PCIeIO).suggestName("pcie")

    // The MIG's internal AXI clock crossing still terminates at its DDR UI clock.
    // Override only the frontend clock; do not re-parent the placed DDR overlay.
    bridge.module.clock := memoryClock.clock
    bridge.module.reset := memoryClock.reset
    migs.foreach { mig =>
      mig.module.clock := memoryClock.clock
      mig.module.reset := memoryClock.reset
    }
    _outer.ddrXbar.foreach { xbar =>
      xbar.module.clock := memoryClock.clock
      xbar.module.reset := memoryClock.reset
    }
    _outer.pcieCpuSource.get.module.clock := referenceClock
    _outer.pcieCpuSource.get.module.reset := hReset

    val pcieRefclk = Module(new IBUFDS_GTE4)
    pcieRefclk.suggestName("pcie_refclk_ibufds")
    pcieRefclk.io.CEB := false.B
    pcieRefclk.io.I := pcie.refclk.p
    pcieRefclk.io.IB := pcie.refclk.n
    val pciePerst = Module(new IBUF)
    pciePerst.suggestName("pcie_perst_ibuf")
    pciePerst.io.I := pcie.perst_n

    val bridgeIO = bridge.module.io
    bridgeIO.pcie.sys_clk := pcieRefclk.io.ODIV2
    bridgeIO.pcie.sys_clk_gt := pcieRefclk.io.O
    bridgeIO.pcie.sys_rst_n := pciePerst.io.O && !_outer.pllReset
    bridgeIO.pcie.pci_exp_rxp := pcie.pci_exp_rxp
    bridgeIO.pcie.pci_exp_rxn := pcie.pci_exp_rxn
    pcie.pci_exp_txp := bridgeIO.pcie.pci_exp_txp
    pcie.pci_exp_txn := bridgeIO.pcie.pci_exp_txn
    // Synchronize each independent DDR UI status before combining them.
    // The endpoint performs the final crossing into XDMA's AXI clock domain.
    if (_outer.dualDDR) {
      val calibReady = withClockAndReset(memoryClock.clock, memoryClock.reset) {
        migs.zipWithIndex.map { case (mig, i) =>
          AsyncResetSynchronizerShiftReg(mig.module.io.port.c0_init_calib_complete,
            2, init = 0, name = Some(s"ddr${i}_calib_memory_sync"))
        }
      }
      bridgeIO.ddrCalibDone := calibReady.reduce(_ && _)
    } else {
      bridgeIO.ddrCalibDone := migs.head.module.io.port.c0_init_calib_complete
    }
    bridgeIO.chipResetObserved := chipReset
    chipReset := ResetCatchAndSync(referenceClock,
      hReset.asBool || bridgeIO.holdChipReset || bridgeIO.axiReset || !bridgeIO.linkUp,
      name = Some("pcie_chip_reset_sync"))

    // VCU118 edge connector: lanes 0-7 in GTY banks 227 and 226.
    def bindPins(ports: Seq[IOPin], pins: Seq[String]): Unit = {
      require(ports.size == pins.size)
      (ports zip pins).foreach { case (port, pin) => _outer.xdc.addPackagePin(port, pin) }
    }
    bindPins(IOPin.of(pcie.pci_exp_rxp), Seq("AA4", "AB2", "AC4", "AD2", "AE4", "AF2", "AG4", "AH2"))
    bindPins(IOPin.of(pcie.pci_exp_rxn), Seq("AA3", "AB1", "AC3", "AD1", "AE3", "AF1", "AG3", "AH1"))
    bindPins(IOPin.of(pcie.pci_exp_txp), Seq("Y7", "AB7", "AD7", "AF7", "AH7", "AK7", "AM7", "AN5"))
    bindPins(IOPin.of(pcie.pci_exp_txn), Seq("Y6", "AB6", "AD6", "AF6", "AH6", "AK6", "AM6", "AN4"))
    _outer.xdc.addPackagePin(pcie.refclk.p, "AC9")
    _outer.xdc.addPackagePin(pcie.refclk.n, "AC8")
    _outer.xdc.addPackagePin(pcie.perst_n, "AM17")
    _outer.xdc.addIOStandard(pcie.perst_n, "LVCMOS18")
    _outer.sdc.addClock("pcie_refclk", pcie.refclk.p, 100)
    _outer.sdc.addGroup(clocks = Seq("pcie_refclk"),
      pins = Seq(bridge.endpoint.module.blackbox.io.axi_aclk))
    _outer.sdc.addAsyncPath(Seq(pciePerst.io.O))
    _outer.sdc.addAsyncPath(Seq(bridge.endpoint.module.blackbox.io.axi_aresetn))
  }

  instantiateChipTops()
}
