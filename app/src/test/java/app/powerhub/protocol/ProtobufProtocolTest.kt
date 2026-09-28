package app.powerhub.protocol

import app.powerhub.proto.Delta3Proto
import app.powerhub.proto.DeltaPro3Proto
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProtobufProtocolTest {

    /** Wraps [pdata] the way the station sends it: optionally XOR-ed with the low byte of seq. */
    private fun frame(cmdFunc: Int, cmdId: Int, pdata: ByteArray, xor: Boolean = true, seq: Int = 0x1234): ByteArray {
        val key = seq and 0xFF
        val wire = if (xor) ByteArray(pdata.size) { (pdata[it].toInt() xor key).toByte() } else pdata
        val header = Delta3Proto.Delta3Header.newBuilder()
            .setSrc(2)
            .setEncType(if (xor) 1 else 0)
            .setSeq(seq)
            .setCmdFunc(cmdFunc)
            .setCmdId(cmdId)
            .setPdata(ByteString.copyFrom(wire))
        return Delta3Proto.Delta3HeaderMessage.newBuilder().addHeader(header).build().toByteArray()
    }

    @Test
    fun `delta 3 display upload is xor-decoded and flattened`() {
        val pdata = Delta3Proto.Delta3DisplayPropertyUpload.newBuilder()
            .setCmsBattSoc(87f)
            .setPowOutSumW(120f)
            .setFlowInfoAcOut(14)
            .build().toByteArray()
        val p = Delta3Protocol.standard.parse(TopicKind.DATA, frame(254, 21, pdata))
        assertEquals(87.0, p["cms_batt_soc"])
        assertEquals(120.0, p["pow_out_sum_w"])
        // Output state is derived from flow_info: 14 means enabled.
        assertEquals(1, p["cfg_ac_out_open"])
        assertEquals(87, Delta3Protocol.standard.state(p).soc)
    }

    @Test
    fun `base64 wrapped frames are accepted`() {
        val pdata = Delta3Proto.Delta3DisplayPropertyUpload.newBuilder().setCmsBattSoc(42f).build().toByteArray()
        val b64 = Base64.getEncoder().encode(frame(254, 21, pdata))
        assertEquals(42.0, Delta3Protocol.standard.parse(TopicKind.DATA, b64)["cms_batt_soc"])
    }

    @Test
    fun `failed set reply is ignored`() {
        val ok = Delta3Proto.Delta3SetReply.newBuilder().setConfigOk(true).setXboostEn(1).build().toByteArray()
        val bad = Delta3Proto.Delta3SetReply.newBuilder().setConfigOk(false).setXboostEn(1).build().toByteArray()
        assertEquals(1, Delta3Protocol.standard.parse(TopicKind.SET_REPLY, frame(254, 18, ok))["xboost_en"])
        assertTrue(Delta3Protocol.standard.parse(TopicKind.SET_REPLY, frame(254, 18, bad)).isEmpty())
    }

    @Test
    fun `delta 3 toggle builds a set packet for the device`() {
        val toggle = Delta3Protocol.standard.controls("SN3").filterIsInstance<Control.Toggle>().first { it.id == "ac_out" }
        val packet = Delta3Proto.Delta3SendHeaderMsg.parseFrom(toggle.command(true, emptyMap()).payload)
        val h = packet.getMsg(0)
        assertEquals(254, h.cmdFunc)
        assertEquals(17, h.cmdId)
        assertEquals(32, h.src)
        assertEquals("SN3", h.deviceSn)
        assertEquals(1, Delta3Proto.Delta3SetCommand.parseFrom(h.pdata).cfgAcOutOpen)
    }

    @Test
    fun `delta 3 ac charge power carries the commit field the firmware expects`() {
        val slider = Delta3Protocol.standard.controls("SN3").filterIsInstance<Control.Slider>().first { it.id == "ac_chg_w" }
        val h = Delta3Proto.Delta3SendHeaderMsg.parseFrom(slider.command(1200, emptyMap()).payload).getMsg(0)
        // field 54 = 1200 (varint b0 09), then field 125 = 0.
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 0x03, 0xB0.toByte(), 0x09, 0xE8.toByte(), 0x07, 0x00), h.pdata.toByteArray())
    }

    @Test
    fun `delta pro 3 telemetry decodes and derives outputs`() {
        val pdata = DeltaPro3Proto.DP3DisplayPropertyUpload.newBuilder()
            .setCmsBattSoc(63f)
            .setPowGetAcIn(0f)
            .setPlugInInfoAcInFlag(0)
            .setFlowInfo12V(4)
            .build().toByteArray()
        val p = DeltaPro3Protocol.parse(TopicKind.DATA, frame(254, 21, pdata))
        val s = DeltaPro3Protocol.state(p)
        assertEquals(63, s.soc)
        assertEquals(false, s.gridConnected)
        assertEquals(0, p["cfg_dc_12v_out_open"])
    }

    @Test
    fun `varint encoding`() {
        assertArrayEquals(byteArrayOf(0x00), ProtoCodec.encodeVarint(0))
        assertArrayEquals(byteArrayOf(0x7F), ProtoCodec.encodeVarint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ProtoCodec.encodeVarint(128))
    }
}
