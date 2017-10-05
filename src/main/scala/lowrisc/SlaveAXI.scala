// See LICENSE.Cambridge for license details.

package freechips.rocketchip.lowrisc

import Chisel._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.coreplex.{HasPeripheryBus, HasMasterAXI4MMIOPortBundle}
import freechips.rocketchip.util.HeterogeneousBag
import scala.collection.mutable.{HashMap, MutableList}
import scala.collection.Map

/** The parameters defining an external device on the MMIO AXI
  * @param name         the name of this device, used in macro
  * @param device       Geneate a device object
  * @param base         The base address of the address space of this device
  * @param size         The size of the address space of this device
  * @param resource     Define a special case for resource binding (currently used for memory blocks)
  * @param interrupts   The number of interrupts generated by this device
  * @param burstBytes   The number of bytes per burst
  * @param readable     Whether the device is readable.
  * @param writeable    Whether the device is writeable.
  * @param executable   Whether the device is executable.
  */
case class ExSlaveParams(
  name: String,
  device: () => SimpleDevice,
  base: BigInt,
  size: BigInt,
  resource: Option[String] = None,
  interrupts: Int = 0,
  burstBytes: Int = 64,  // needs to be set >= 64
  readable: Boolean = true,
  writeable: Boolean = true,
  executable: Boolean = false
)

/** A collection of parameters to define all external devices
  * @param beatBytes    the bus datawidth of the shared mmio port
  * @param idBits       the user id size used to bookkeep control flow
  * @param slaves       the parameters of individual slave devices
  */
case class ExPeriperalsParams(
  beatBytes: Int,
  idBits: Int,
  slaves: Seq[ExSlaveParams]
)

case object ExPeriperals extends Field[ExPeriperalsParams]

case class AXI4VirtualBusNode(
  masterFn: Seq[AXI4MasterPortParameters] => AXI4MasterPortParameters,
  slaveFn:  Seq[AXI4SlavePortParameters]  => AXI4SlavePortParameters
) extends VirtualBusNode(AXI4Imp)(masterFn, slaveFn)

case class AXI4VirtualSlaveNode(portParams: Seq[AXI4SlavePortParameters])
  extends VirtualSlaveNode(AXI4Imp)(portParams)

trait HasAXI4VirtualBus extends HasPeripheryBus {

  // a virtual bus used as the attach point of slaves
  // also this virtual bus produce the actual mmio output port
  val mmio_axi4 = AXI4VirtualBusNode(
    masterFn = { m => {
      require(m.size == 1)
      m(0)
    }},
    slaveFn = { s =>
      AXI4SlavePortParameters(
        s.map(_.slaves).flatten,
        s.map(_.beatBytes).min,
        s.map(_.minLatency).min
      )}
  )

  // generate and attach virtual slaves
  var int_size = 0
  var int_nodes = MutableList[IntInternalInputNode]()
  p(ExPeriperals).slaves.foreach( d => {
    // set up a device for device tree descriptor
    val device = d.device()

    // set up a virtual node for connection, address space propagation
    val slave = AXI4VirtualSlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(d.base, d.size - 1)),
          resources     = d.resource.map(device.reg(_)).getOrElse(device.reg),
          executable    = d.executable,
          supportsWrite = if(d.writeable) TransferSizes(1, d.burstBytes) else TransferSizes.none,
          supportsRead  = if(d.readable) TransferSizes(1, d.burstBytes) else TransferSizes.none
        )),
        beatBytes = p(ExPeriperals).beatBytes
      )))
    slave :*=  mmio_axi4

    // dump SV marcos
    val slave_macro_name = d.name.toUpperCase
    DumpMacro("USE_" ++ slave_macro_name)
    DumpMacro(slave_macro_name ++ "_BASE", d.base)
    DumpMacro(slave_macro_name ++ "_SIZE", d.size) // potentially to support mask instead of size

    // interrupts
    if(d.interrupts > 0) {
      val intnode = IntInternalInputNode(IntSourcePortSimple(num = d.interrupts, resources = device.int))
      ibus.fromSync := intnode

      int_nodes += intnode
      DumpMacro("INT_" ++ slave_macro_name ++ "_BASE", int_size)
      DumpMacro("INT_" ++ slave_macro_name ++ "_SIZE", d.interrupts)
      int_size += d.interrupts
    }
    if(int_size > 0) {
      DumpMacro("USE_ROCKET_INT")
      DumpMacro("ROCKET_INT_SIZE", int_size)
    }
  })

  // connect the mmio port to peripheral bus
  mmio_axi4 :=
    AXI4Buffer()(
    AXI4UserYanker()(
    AXI4Deinterleaver(pbus.blockBytes)(
    AXI4IdIndexer(p(ExPeriperals).idBits)(
    TLToAXI4(p(ExPeriperals).beatBytes)(
    pbus.toVariableWidthSlaves)))))

}

trait HasAXI4VirtualBusBundle {
  implicit val p: Parameters
  val mmio_axi4: HeterogeneousBag[AXI4Bundle]
  val mmio_interrupts: UInt
}

trait HasAXI4VirtualBusModuleImp extends LazyMultiIOModuleImp with HasAXI4VirtualBusBundle {
  val outer: HasAXI4VirtualBus
  val mmio_axi4 = IO(outer.mmio_axi4.bundleOut)
  val mmio_interrupts = IO(UInt(INPUT, width = outer.int_size))
  outer.int_nodes.map(_.bundleIn).flatten.flatten.zipWithIndex.foreach {case(o,i) => o := mmio_interrupts(i)}
}
