package example

import chisel3._
import chisel3.util.{log2Up}

import freechips.rocketchip.config.{Field, Parameters, Config}
import freechips.rocketchip.subsystem.{SystemBusKey, RocketTilesKey, WithRoccExample, WithNMemoryChannels, WithNBigCores, WithRV32, CacheBlockBytes}
import freechips.rocketchip.diplomacy.{LazyModule, ValName}
import freechips.rocketchip.devices.tilelink.BootROMParams
import freechips.rocketchip.tile.{RocketTileParams, MaxHartIdBits, XLen, BuildRoCC, TileKey, LazyRoCC}
import freechips.rocketchip.rocket.{RocketCoreParams, MulDivParams, DCacheParams, ICacheParams}

import boom.common.{BoomTilesKey}

import testchipip._

import hwacha.{Hwacha}

import sifive.blocks.devices.gpio._

import icenet.{NICKey, NICConfig}

import memblade.cache.{DRAMCacheKey, DRAMCacheConfig}
import memblade.client.{RemoteMemClientKey, RemoteMemClientConfig}
import memblade.manager.{MemBladeKey, MemBladeParams, MemBladeQueueParams}
import memblade.prefetcher.{SequentialPrefetchConfig}

import scala.math.max

/**
 * TODO: Why do we need this?
 */
object ConfigValName {
  implicit val valName = ValName("TestHarness")
}
import ConfigValName._

// -----------------------
// Common Parameter Mixins
// -----------------------

/**
 * Class to specify where the BootRom file is (from `rebar` top)
 */
class WithBootROM extends Config((site, here, up) => {
  case BootROMParams => BootROMParams(
    contentFileName = s"./bootrom/bootrom.rv${site(XLen)}.img")
})

// DOC include start: WithGPIO
/**
 * Class to add in GPIO
 */
class WithGPIO extends Config((site, here, up) => {
  case PeripheryGPIOKey => List(
    GPIOParams(address = 0x10012000, width = 4, includeIOF = false))
})
// DOC include end: WithGPIO

// -----------------------------------------------
// BOOM and/or Rocket Top Level System Parameter Mixins
// -----------------------------------------------

/**
 * Class to specify a "plain" top level BOOM and/or Rocket system
 */
class WithTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    Module(LazyModule(new Top()(p)).module)
  }
})

/**
 * Class to specify a top level BOOM and/or Rocket system with DTM
 */
class WithDTMTop extends Config((site, here, up) => {
  case BuildTopWithDTM => (clock: Clock, reset: Bool, p: Parameters) => {
    Module(LazyModule(new TopWithDTM()(p)).module)
  }
})

/**
 * Class to specify a top level BOOM and/or Rocket system with PWM
 */
// DOC include start: WithPWMTop
class WithPWMTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) =>
    Module(LazyModule(new TopWithPWMTL()(p)).module)
})
// DOC include end: WithPWMTop

/**
 * Class to specify a top level BOOM and/or Rocket system with a PWM AXI4
 */
class WithPWMAXI4Top extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) =>
    Module(LazyModule(new TopWithPWMAXI4()(p)).module)
})

/**
 * Class to specify a top level BOOM and/or Rocket system with a TL-attached GCD device
 */
class WithGCDTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) =>
    Module(LazyModule(new TopWithGCD()(p)).module)
})

/**
 * Class to specify a top level BOOM and/or Rocket system with a block device
 */
class WithBlockDeviceModelTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    val top = Module(LazyModule(new TopWithBlockDevice()(p)).module)
    top.connectBlockDeviceModel()
    top
  }
})

/**
 * Class to specify a top level BOOM and/or Rocket system with a simulator block device
 */
class WithSimBlockDeviceTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    val top = Module(LazyModule(new TopWithBlockDevice()(p)).module)
    top.connectSimBlockDevice(clock, reset)
    top
  }
})

// DOC include start: WithGPIOTop
/**
 * Class to specify a top level BOOM and/or Rocket system with GPIO
 */
class WithGPIOTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    val top = Module(LazyModule(new TopWithGPIO()(p)).module)
    for (gpio <- top.gpio) {
      for (pin <- gpio.pins) {
        pin.i.ival := false.B
      }
    }
    top
  }
})
// DOC include end: WithGPIOTop

// ------------------
// Multi-RoCC Support
// ------------------

/**
 * Map from a hartId to a particular RoCC accelerator
 */
case object MultiRoCCKey extends Field[Map[Int, Seq[Parameters => LazyRoCC]]](Map.empty[Int, Seq[Parameters => LazyRoCC]])

/**
 * Mixin to enable different RoCCs based on the hartId
 */
class WithMultiRoCC extends Config((site, here, up) => {
  case BuildRoCC => site(MultiRoCCKey).getOrElse(site(TileKey).hartId, Nil)
})

/**
 * Mixin to add Hwachas to cores based on hart
 *
 * For ex:
 *   Core 0, 1, 2, 3 have been defined earlier
 *     with hartIds of 0, 1, 2, 3 respectively
 *   And you call WithMultiRoCCHwacha(0,1)
 *   Then Core 0 and 1 will get a Hwacha
 *
 * @param harts harts to specify which will get a Hwacha
 */
class WithMultiRoCCHwacha(harts: Int*) extends Config((site, here, up) => {
  case MultiRoCCKey => {
    require(harts.max <= ((up(RocketTilesKey, site).length + up(BoomTilesKey, site).length) - 1))
    up(MultiRoCCKey, site) ++ harts.distinct.map{ i =>
      (i -> Seq((p: Parameters) => {
        LazyModule(new Hwacha()(p)).suggestName("hwacha")
      }))
    }
  }
})

class WithRemoteMemClient(spanBytes: Int = 1024)
    extends Config((site, here, up) => {
  case RemoteMemClientKey => RemoteMemClientConfig(
    spanBytes = spanBytes,
    nRMemXacts = 32768 / spanBytes)
})

class WithMemBlade(spanBytes: Option[Int] = None)
    extends Config((site, here, up) => {
  case MemBladeKey => {
    val spanBytesVal = spanBytes.getOrElse(site(CacheBlockBytes))
    MemBladeParams(
      spanBytes = spanBytesVal,
      nSpanTrackers = max(384 / spanBytesVal, 2),
      spanQueue = MemBladeQueueParams(reqHeadDepth = 32, respHeadDepth = 32),
      wordQueue = MemBladeQueueParams(reqHeadDepth = 32, respHeadDepth = 32))
  }
})

class WithRemoteMemClientTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    val top = Module(LazyModule(new TopWithRemoteMemClient()(p)).module)
    top.connectTestMemBlade()
    top
  }
})

class WithDRAMCache(
    sizeKB: Int,
    nWays: Int = 7,
    nTrackersPerBank: Int = 1,
    nBanksPerChannel: Int = 1) extends Config((site, here, up) => {
  case DRAMCacheKey => {
    val spanBytes = site(CacheBlockBytes)
    val nSets = (sizeKB * 1024) / (nWays * spanBytes)
    DRAMCacheConfig(
      nSets = nSets,
      nWays = nWays,
      spanBytes = spanBytes,
      baseAddr = 1L << 32,
      extentBytes = 1 << 20,
      logAddrBits = 28,
      nTrackersPerBank = nTrackersPerBank,
      nBanksPerChannel = nBanksPerChannel,
      zeroMetadata = true)
  }
})

class WithDRAMCacheTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    val top = Module(LazyModule(new TopWithDRAMCache()(p)).module)
    top.connectTestMemBlade(100)
    top.connectSimAXICacheMem()
    top
  }
})

// DOC include start: WithInitZero
class WithInitZero(base: BigInt, size: BigInt) extends Config((site, here, up) => {
  case InitZeroKey => InitZeroConfig(base, size)
})

class WithInitZeroTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) =>
    Module(LazyModule(new TopWithInitZero()(p)).module)
})
// DOC include end: WithInitZero

/**
 * Mixin to add a small Rocket core to the system as a "control" core.
 * Used as an example of a PMU core.
 */
class WithControlCore extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) :+
    RocketTileParams(
      core = RocketCoreParams(
        useVM = false,
        fpu = None,
        mulDiv = Some(MulDivParams(mulUnroll = 8))),
      btb = None,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBEntries = 4,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBEntries = 4,
        blockBytes = site(CacheBlockBytes))),
      hartId = up(RocketTilesKey, site).size + up(BoomTilesKey, site).size
    )
  case MaxHartIdBits => log2Up(up(RocketTilesKey, site).size + up(BoomTilesKey, site).size + 1)
})

class WithIceNIC(inBufFlits: Int = 1800, usePauser: Boolean = false)
    extends Config((site, here, up) => {
  case NICKey => NICConfig(
    inBufFlits = inBufFlits,
    usePauser = usePauser,
    checksumOffload = true)
})

class WithLoopbackNICTop extends Config((site, here, up) => {
  case BuildTop => (clock: Clock, reset: Bool, p: Parameters) => {
    val top = Module(LazyModule(new TopWithIceNIC()(p)).module)
    top.connectNicLoopback()
    top
  }
})

class WithHwachaConfPrec extends Config((site, here, up) => {
  case hwacha.HwachaConfPrec => true
  // With ConfPrec on, there can 4x the number of elements per SRAM entry
  case hwacha.HwachaMaxVLen => up(hwacha.HwachaMaxVLen) * 4
})
