package chipyard

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.AsyncResetSynchronizerShiftReg

/** Stop accepting new memory transactions, then wait for all replies to drain. */
class TLMemDelayControl extends Bundle {
  val stopNewRequests = Input(Bool())
  val idle = Output(Bool())
}

class TLMemoryQuiescer(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(clientFn = x => x, managerFn = x => x)
  val control = BundleBridgeSource(() => new TLMemDelayControl)
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val ctrl = control.bundle
    val idle = (node.in zip node.out).map { case ((in, edge), (out, _)) =>
      require(!edge.manager.anySupportAcquireB, "Memory delay supports the uncached external TL port")
      out <> in
      val (aFirst, _, _) = edge.firstlast(in.a)
      val (_, dLast, _) = edge.firstlast(in.d)

      // A first beat that has already been presented cannot be withdrawn when
      // stop rises. Finish that transaction, including every Put data beat.
      val presented = RegInit(false.B)
      val allow = !ctrl.stopNewRequests || !aFirst || presented
      out.a.valid := in.a.valid && allow
      in.a.ready := out.a.ready && allow
      when(out.a.valid && !out.a.ready) { presented := true.B }
      when(out.a.fire) { presented := false.B }

      val outstanding = RegInit(0.U(log2Ceil(edge.client.endSourceId + 1).W))
      val started = in.a.fire && aFirst
      val finished = in.d.fire && dLast
      when(started =/= finished) {
        outstanding := Mux(started, outstanding + 1.U, outstanding - 1.U)
      }
      when(finished && !started) { assert(outstanding =/= 0.U, "Memory response without a request") }
      when(started && !finished) { assert(outstanding < edge.client.endSourceId.U, "Too many memory requests") }
      outstanding === 0.U && aFirst && !presented
    }
    ctrl.idle := idle.reduceOption(_ && _).getOrElse(true.B)
  }
}

/** Uses the infrastructure reset, which must also reset the external memory
  * fabric. CPU-only reset is granted only after the memory port has drained.
  */
class MemoryResetController extends Module {
  val io = IO(new Bundle {
    val request = Input(Bool())
    val idle = Input(Bool())
    val stopNewRequests = Output(Bool())
    val resetGranted = Output(Bool())
  })
  val requestSync = AsyncResetSynchronizerShiftReg(io.request, 2, init = 1,
    name = Some("memory_reset_request_sync"))
  val granted = RegNext(requestSync && io.idle, true.B)
  io.stopNewRequests := requestSync
  // Registering the grant ensures stop is active before the idle decision can
  // reset a request accepted at the same clock edge.
  io.resetGranted := granted
}
