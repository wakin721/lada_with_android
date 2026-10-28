package com.wakin.lada
import org.junit.Assert.*
import org.junit.Test

class PreviewMathTest {
    @Test fun originalModelUsesBgrButDetectorUsesRgb() {
        val pixel=0xff123456.toInt()
        val bgr=PixelTensors.planar(intArrayOf(pixel),true)
        val rgb=PixelTensors.planar(intArrayOf(pixel),false)
        assertArrayEquals(floatArrayOf(0x56/255f,0x34/255f,0x12/255f),bgr,1e-6f)
        assertArrayEquals(floatArrayOf(0x12/255f,0x34/255f,0x56/255f),rgb,1e-6f)
        assertEquals(pixel,PixelTensors.bgrToArgb(bgr,0,1)[0])
    }
    @Test fun lightweightRgbContractAndBatchOffsetsStayDistinct() {
        val pixels=intArrayOf(0xffaa3322.toInt())
        val rgb=PixelTensors.planar(pixels,false)
        assertEquals(pixels[0],PixelTensors.planarToArgb(rgb,0,1,false)[0])
        val bgr=PixelTensors.planar(pixels,true)
        val batch=FloatArray(9)
        System.arraycopy(bgr,0,batch,3,3)
        assertEquals(pixels[0],PixelTensors.bgrToArgb(batch,3,1)[0])
    }
    @Test fun stopRejectsLateFramesAndOldFinallyCannotStopNewPlayback() {
        val gate=PlaybackGeneration()
        val old=gate.begin()
        assertTrue(gate.isCurrent(old))
        gate.stop()
        assertFalse(gate.isRunning())
        assertFalse(gate.isCurrent(old))
        val next=gate.begin()
        assertFalse(gate.finish(old))
        assertTrue(gate.isCurrent(next))
        assertTrue(gate.finish(next))
        assertFalse(gate.isRunning())
    }
    @Test fun bothLightSizesDriveTheRealTensorShapeAndRejectUnsupportedSizes() {
        assertArrayEquals(longArrayOf(1,3,128,128),lightModelShape(128))
        assertArrayEquals(longArrayOf(1,3,256,256),lightModelShape(256))
        try {lightModelShape(192);fail("Unsupported size must be rejected")}catch(expected:IllegalArgumentException){}
    }
    @Test fun frameNeighborsStayInsideClip() {
        assertEquals(listOf(0L,0L,33L),neighborTimes(0,1000,1000.0/30))
        assertEquals(listOf(966L,999L,999L),neighborTimes(999,1000,1000.0/30))
    }
    @Test fun cropIsSquareAndInsideLandscapeAndPortrait() {
        for((w,h) in listOf(960 to 540,540 to 960)) {
            val area=Region(.8f,.85f,1.2f,1.1f).square(w,h)
            assertTrue(area.left>=0&&area.top>=0&&area.right<=w&&area.bottom<=h)
            val roundTrip=area.normalized(w,h).square(w,h)
            assertTrue(kotlin.math.abs(roundTrip.side-area.side)<=1)
        }
    }
    @Test fun duplicateCandidatesAreSuppressedByConfidence() {
        val box=Region(.2f,.2f,.6f,.6f)
        val far=Region(.8f,.8f,.9f,.9f)
        val result=suppressOverlaps(listOf(Candidate(box,.4f),Candidate(box,.9f),Candidate(far,.6f)))
        assertEquals(2,result.size)
        assertEquals(.9f,result.first().confidence,0f)
    }
}
