//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package chipyard

import chisel3._

import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{DontTouch}

// ---------------------------------------------------------------------
// Base system that uses the debug test module (dtm) to bringup the core
// ---------------------------------------------------------------------

/**
 * Base top with periphery devices and ports, and a BOOM + Rocket subsystem
 */
class ChipyardSystem(implicit p: Parameters) extends ChipyardSubsystem
  with HasAsyncExtInterrupts
  with CanHaveMasterTLMemPort // export TL port for outer memory
  with CanHaveMasterAXI4MemPort // expose AXI port for outer mem
  with CanHaveMasterAXI4MMIOPort
  with CanHaveSlaveAXI4Port
{

  val bootROM  = p(BootROMLocated(location)).map { BootROM.attach(_, this, CBUS) }
  val maskROMs = p(MaskROMLocated(location)).map { MaskROM.attach(_, this, CBUS) }

  override lazy val module = new ChipyardSystemModule(this)
}

/**
 * Base top module implementation with periphery devices and ports, and a BOOM + Rocket subsystem
 */
class ChipyardSystemModule(_outer: ChipyardSystem) extends ChipyardSubsystemModuleImp(_outer)
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with DontTouch

// ------------------------------------
// TL Mem Port Mixin
// ------------------------------------

// Similar to ExtMem but instantiates a TL mem port
case object ExtTLMem extends Field[Option[MemoryPortParams]](None)

/** Additional registered stages in each external-memory A/D path. */
case object ExtTLMemExtraDelayStages extends Field[Int](0)

/** Adds a port to the system intended to master an TL DRAM controller. */
trait CanHaveMasterTLMemPort { this: BaseSubsystem =>

  require(!(p(ExtTLMem).nonEmpty && p(ExtMem).nonEmpty),
    "Can only have 1 backing memory port. Use ExtTLMem for a TL memory port or ExtMem for an AXI memory port.")

  private val memPortParamsOpt = p(ExtTLMem)
  private val portName = "tl_mem"
  private val device = new MemoryDevice
  private val idBits = memPortParamsOpt.map(_.master.idBits).getOrElse(1)
  private val mbus = tlBusWrapperLocationMap.lift(MBUS).getOrElse(locateTLBusWrapper(SBUS))

  val memTLNode = TLManagerNode(memPortParamsOpt.map({ case MemoryPortParams(memPortParams, nMemoryChannels, _) =>
    Seq.tabulate(nMemoryChannels) { channel =>
      val base = AddressSet.misaligned(memPortParams.base, memPortParams.size)
      val filter = AddressSet(channel * mbus.blockBytes, ~((nMemoryChannels-1) * mbus.blockBytes))

     TLSlavePortParameters.v1(
       managers = Seq(TLSlaveParameters.v1(
         address            = base.flatMap(_.intersect(filter)),
         resources          = device.reg,
         regionType         = RegionType.UNCACHED, // cacheable
         executable         = true,
         supportsGet        = TransferSizes(1, mbus.blockBytes),
         supportsPutFull    = TransferSizes(1, mbus.blockBytes),
         supportsPutPartial = TransferSizes(1, mbus.blockBytes))),
         beatBytes = memPortParams.beatBytes)
    }
  }).toList.flatten)

  // disable inwards monitors from node since the class with this trait
  // doesn't provide an implicit clock to those monitors
  require(p(ExtTLMemExtraDelayStages) >= 0, "External memory delay must be nonnegative")
  require(p(ExtTLMemExtraDelayStages) == 0 || memPortParamsOpt.nonEmpty, "External TL memory delay requires a TL memory port")

  private val memDelayQuiescer = mbus.coupleTo(s"memory_controller_port_named_$portName") { bus =>
    val (port, quiescer) = DisableMonitors { implicit p =>
      val q = if (p(ExtTLMemExtraDelayStages) > 0) Some(LazyModule(new TLMemoryQuiescer)) else None
      val qNode = q.map(_.node).getOrElse(TLNameNode("no_memory_quiescer"))
      (memTLNode :*= TLBuffer.chainNode(p(ExtTLMemExtraDelayStages), Some("extmem_delay"))
        :*= qNode :*= TLBuffer(), q)
    }
    port :*= TLSourceShrinker(1 << idBits) :*= TLWidthWidget(mbus.beatBytes) :*= bus
    quiescer
  }

  val mem_tl = InModuleBody { memTLNode.makeIOs() }
  private val memDelayControlSink = memDelayQuiescer.map(_.control.makeSink())
  val mem_delay_control = InModuleBody {
    memDelayControlSink.map(_.makeIO("mem_delay_control"))
  }
}
