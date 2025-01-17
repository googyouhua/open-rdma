package rdma

import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class SqDmaReadRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  def busWidthBytes: Int = busWidth.id / BYTE_WIDTH

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SqDmaReadRespHandler(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val psnQueue = mutable.Queue[Int]()
      val matchQueue = mutable.Queue[Int]()

      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.cachedWorkReq, dut.clockDomain) {
        dut.io.cachedWorkReq.workReq.lenBytes #= 0
      }
      onStreamFire(dut.io.cachedWorkReq, dut.clockDomain) {
        psnQueue.enqueue(dut.io.cachedWorkReq.psnStart.toInt)
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        dut.io.dmaReadResp.resp.ready.toBoolean == false
      }
      streamSlaveRandomizer(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain)
      onStreamFire(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
//        println(
//            f"the read request has zero DMA length, but dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong}%X"
//        )
        assert(
          dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong == 0,
          f"the read request has zero DMA length, but dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong}%X"
        )

        val inputPsnStart = psnQueue.dequeue()
//        println(
//            f"output PSN io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt}%X not match input PSN io.cachedWorkReq.psnStart=${inputPsnStart}%X"
//        )
        assert(
          inputPsnStart == dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt,
          f"output PSN io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt}%X not match input PSN io.cachedWorkReq.psnStart=${inputPsnStart}%X"
        )

        matchQueue.enqueue(inputPsnStart)
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val cacheDataQueue = mutable.Queue[(Int, Int, Long)]()
      val dmaRespQueue = mutable.Queue[(BigInt, Int, Long, Boolean)]()
      val outputQueue =
        mutable.Queue[(BigInt, Int, Int, Long, Long, Int, Boolean)]()
      val matchQueue = mutable.Queue[Int]()

      val pmtuBytes = 256
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      val (_, pktNumItr4CacheData, psnItr4CacheData, totalLenItr4CacheData) =
        SendWriteReqReadRespInput.getItr(pmtuBytes, busWidthBytes)
      streamMasterDriver(dut.io.cachedWorkReq, dut.clockDomain) {
        val pktNum = pktNumItr4CacheData.next()
        val psnStart = psnItr4CacheData.next()
        val totalLenBytes = totalLenItr4CacheData.next()

        dut.io.cachedWorkReq.workReq.lenBytes #= totalLenBytes
        dut.io.cachedWorkReq.psnStart #= psnStart
        dut.io.cachedWorkReq.pktNum #= pktNum
      }
      onStreamFire(dut.io.cachedWorkReq, dut.clockDomain) {
//        println(
//          f"dut.io.cachedWorkReq.psnStart=${dut.io.cachedWorkReq.psnStart.toInt}, dut.io.cachedWorkReq.pktNum=${dut.io.cachedWorkReq.pktNum.toInt}, dut.io.cachedWorkReq.workReq.lenBytes=${dut.io.cachedWorkReq.workReq.lenBytes.toLong}"
//        )
        cacheDataQueue.enqueue(
          (
            dut.io.cachedWorkReq.psnStart.toInt,
            dut.io.cachedWorkReq.pktNum.toInt,
            dut.io.cachedWorkReq.workReq.lenBytes.toLong
          )
        )
      }

      // Functional way to generate sequences
      val (fragNumItr4DmaResp, _, psnItr4DmaResp, totalLenItr4DmaResp) =
        SendWriteReqReadRespInput.getItr(pmtuBytes, busWidthBytes)
      fragmentStreamMasterDriver(dut.io.dmaReadResp.resp, dut.clockDomain) {
        val fragNum = fragNumItr4DmaResp.next()
        val totalLenBytes = totalLenItr4DmaResp.next()
        val psnStart = psnItr4DmaResp.next()
        (fragNum, (psnStart, totalLenBytes))
      } { (fragIdx, outerLoopRslt) =>
        val (fragNum, (psnStart, totalLenBytes)) = outerLoopRslt
        dut.io.dmaReadResp.resp.psnStart #= psnStart
        dut.io.dmaReadResp.resp.lenBytes #= totalLenBytes
        dut.io.dmaReadResp.resp.last #= (fragIdx == fragNum - 1)
      }
      onStreamFire(dut.io.dmaReadResp.resp, dut.clockDomain) {
        println(
          f"dut.io.dmaReadResp.resp.psnStart=${dut.io.dmaReadResp.resp.psnStart.toInt}, dut.io.dmaReadResp.resp.lenBytes=${dut.io.dmaReadResp.resp.lenBytes.toLong}, dut.io.dmaReadResp.resp.last=${dut.io.dmaReadResp.resp.last.toBoolean}"
        )
        dmaRespQueue.enqueue(
          (
            dut.io.dmaReadResp.resp.data.toBigInt,
            dut.io.dmaReadResp.resp.psnStart.toInt,
            dut.io.dmaReadResp.resp.lenBytes.toLong,
            dut.io.dmaReadResp.resp.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain)
      onStreamFire(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.data.toBigInt,
            dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.psnStart.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong,
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.lenBytes.toLong,
            dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.pktNum.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.last.toBoolean
          )
        )
      }

      // Check DUT output
      fork {
        while (true) {
          val (psnStartInCache, pktNumIn, respLenInCache) =
            MiscUtils.safeDeQueue(cacheDataQueue, dut.clockDomain)

          var isFragEnd = false
          do {
            val (dmaRespDataIn, psnStartInDmaResp, respLenInDmaResp, isLastIn) =
              MiscUtils.safeDeQueue(dmaRespQueue, dut.clockDomain)
            val (
              dmaRespDataOut,
              psnStartOutCache,
              psnStartOutDmaResp,
              respLenOutCache,
              respLenOutDmaResp,
              pktNumOut,
              isLastOut
            ) = MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

//            println(
//              f"psnStartInCache=${psnStartInCache} == psnStartOutCache=${psnStartOutCache}, psnStartInDmaResp=${psnStartInDmaResp} == psnStartOutDmaResp=${psnStartOutDmaResp}, psnStartInCache=${psnStartInCache} == psnStartInDmaResp=${psnStartInDmaResp}"
//            )
            assert(
              psnStartInCache == psnStartOutCache &&
                psnStartInDmaResp == psnStartOutDmaResp &&
                psnStartInCache == psnStartInDmaResp,
              f"psnStartInCache=${psnStartInCache} == psnStartOutCache=${psnStartOutCache}, psnStartInDmaResp=${psnStartInDmaResp} == psnStartOutDmaResp=${psnStartOutDmaResp}, psnStartInCache=${psnStartInCache} == psnStartInDmaResp=${psnStartInDmaResp}"
            )

//          println(
//            f"output packet num=${dut.io.cachedWorkReqAndDmaReadResp.resultCacheData.pktNum.toInt} not match input packet num=${pktNumIn}%X"
//          )
            assert(
              pktNumIn == pktNumOut,
              f"output packet num=${pktNumOut} not match input packet num=${pktNumIn}%X"
            )

//            println(
//              f"respLenInDmaResp=${respLenInDmaResp} == respLenOutDmaResp=${respLenOutDmaResp}, respLenInCache=${respLenInCache} == respLenOutCache=${respLenOutCache}, respLenInDmaResp=${respLenInDmaResp} == respLenInCache=${respLenInCache}"
//            )
            assert(
              respLenInDmaResp == respLenOutDmaResp &&
                respLenInCache == respLenOutCache &&
                respLenInDmaResp == respLenInCache,
              f"respLenInDmaResp=${respLenInDmaResp} == respLenOutDmaResp=${respLenOutDmaResp}, respLenInCache=${respLenInCache} == respLenOutCache=${respLenOutCache}, respLenInDmaResp=${respLenInDmaResp} == respLenInCache=${respLenInCache}"
            )

//          println(
//            f"output response data io.cachedWorkReqAndDmaReadResp.dmaReadResp.data=${dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.data.toBigInt}%X not match input response data io.dmaReadResp.resp.data=${dataIn}%X"
//          )
            assert(
              dmaRespDataIn.toString(16) == dmaRespDataOut.toString(16),
              f"output response data io.cachedWorkReqAndDmaReadResp.dmaReadResp.data=${dmaRespDataOut}%X not match input response data io.dmaReadResp.resp.data=${dmaRespDataIn}%X"
            )

            assert(
              isLastIn == isLastOut,
              f"output dut.io.cachedWorkReqAndDmaReadResp.last=${isLastOut} not match input dut.io.dmaReadResp.resp.last=${isLastIn}"
            )

            matchQueue.enqueue(psnStartInDmaResp)
            isFragEnd = isLastOut
          } while (!isFragEnd)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}
