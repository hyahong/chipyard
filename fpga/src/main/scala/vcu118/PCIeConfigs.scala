package chipyard.fpga.vcu118

import org.chipsalliance.cde.config.{Config, Field}
import chipyard.harness.HarnessBinder
import chipyard.iobinders.ResetPort

/** Enable the host DMA path without changing the existing VCU118 configurations. */
case object VCU118PCIeKey extends Field[Boolean](false)

/** Map the two physical DDR interfaces into one contiguous memory region. */
case object VCU118DualDDRKey extends Field[Boolean](false)

class WithVCU118DualDDR extends Config((site, here, up) => {
  case VCU118DualDDRKey => true
})

class WithVCU118PCIe extends Config((site, here, up) => {
  case VCU118PCIeKey => true
})

/** ChipTop reset is independent of the PCIe, memory fabric, and DDR controller resets. */
class WithPCIeChipReset extends HarnessBinder({
  case (th: VCU118FPGATestHarnessImp, port: ResetPort, chipId: Int)
      if th.vcu118Outer.pcieEnabled => {
    port.io := th.chipReset.asAsyncReset
  }
})

/** Initial DMA-to-DDR test design: the host control block keeps ChipTop in reset.
  * The existing SD-card boot ROM is retained; release/boot support is a later step.
  */
class BoomVCU118PCIeConfig extends Config(
  new WithPCIeChipReset ++
  new WithVCU118PCIe ++
  new BoomVCU118Config
)

/** Two 2 GiB MIG instances; ChipTop remains held in reset for DMA validation. */
class BoomVCU118PCIe4GiBConfig extends Config(
  new WithVCU118DualDDR ++
  new BoomVCU118PCIeConfig
)
