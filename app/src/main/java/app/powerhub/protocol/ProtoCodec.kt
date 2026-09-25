package app.powerhub.protocol

import android.util.Base64
import app.powerhub.proto.Delta3Proto
import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message

/**
 * Framing shared by the protobuf devices (Delta 3 family, Delta Pro 3).
 * Every MQTT payload is a HeaderMessage { repeated Header header = 1 }, each Header carries
 * cmd_func / cmd_id and a `pdata` blob that is optionally XOR-ed with the low byte of `seq`.
 * Delta 3 and Delta Pro 3 headers are wire-identical, so the Delta 3 classes serve both.
 */
object ProtoCodec {
    class Frame(val cmdFunc: Int, val cmdId: Int, val pdata: ByteArray)

    private const val SRC_APP = 32

    fun frames(payload: ByteArray): List<Frame> {
        val raw = maybeBase64(payload)
        val msg = runCatching { Delta3Proto.Delta3HeaderMessage.parseFrom(raw) }.getOrNull() ?: return emptyList()
        return msg.headerList.mapNotNull { h ->
            if (!h.hasPdata() || h.pdata.isEmpty) return@mapNotNull null
            var data = h.pdata.toByteArray()
            if (h.encType == 1 && h.src != SRC_APP) {
                val key = h.seq and 0xFF
                data = ByteArray(data.size) { i -> (data[i].toInt() xor key).toByte() }
            }
            Frame(h.cmdFunc, h.cmdId, data)
        }
    }

    private fun maybeBase64(payload: ByteArray): ByteArray {
        if (payload.isEmpty() || payload.size % 4 != 0) return payload
        val isB64 = payload.all { b ->
            val c = b.toInt().toChar()
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='
        }
        if (!isB64) return payload
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrDefault(payload)
    }

    /** Decodes [pdata] with [descriptor] and flattens nested messages using `_` as separator. */
    fun decodeFlat(descriptor: Descriptor, pdata: ByteArray): MutableMap<String, Any?> {
        val msg = runCatching { DynamicMessage.parseFrom(descriptor, pdata) }.getOrNull() ?: return HashMap()
        return flatten(msg)
    }

    fun flatten(msg: Message, prefix: String = "", out: MutableMap<String, Any?> = HashMap()): MutableMap<String, Any?> {
        for ((fd, value) in msg.allFields) {
            val key = if (prefix.isEmpty()) fd.name else "${prefix}_${fd.name}"
            if (fd.isRepeated) {
                out[key] = (value as List<*>).map { convert(fd, it) }
            } else if (fd.javaType == FieldDescriptor.JavaType.MESSAGE) {
                flatten(value as Message, key, out)
            } else {
                out[key] = convert(fd, value)
            }
        }
        return out
    }

    private fun convert(fd: FieldDescriptor, v: Any?): Any? = when (fd.javaType) {
        FieldDescriptor.JavaType.FLOAT -> (v as Float).toDouble()
        FieldDescriptor.JavaType.ENUM -> (v as EnumValueDescriptor).number
        FieldDescriptor.JavaType.BYTE_STRING -> null
        FieldDescriptor.JavaType.MESSAGE -> flatten(v as Message)
        else -> v
    }

    /** Builds a SetCommand payload with a single scalar field set. */
    fun setCommandPdata(descriptor: Descriptor, field: String, value: Int): ByteArray {
        val fd = requireNotNull(descriptor.findFieldByName(field)) { "Unknown field $field" }
        return DynamicMessage.newBuilder(descriptor).setField(fd, value).build().toByteArray()
    }

    fun encodeVarint(v: Int): ByteArray {
        var x = v.toLong() and 0xFFFFFFFFL
        val out = ArrayList<Byte>()
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) out.add((b or 0x80).toByte()) else { out.add(b.toByte()); break }
        }
        return out.toByteArray()
    }

    /**
     * Wraps a SetCommand into the packet the official app sends:
     * src=32 (app) → dest=2, cmd_func=254 cmd_id=17, version=19.
     */
    fun setPacket(sn: String, pdata: ByteArray, dataLen: Int = pdata.size): Outgoing {
        val header = Delta3Proto.Delta3Header.newBuilder()
            .setSrc(SRC_APP)
            .setDest(2)
            .setDSrc(1)
            .setDDest(1)
            .setCmdFunc(254)
            .setCmdId(17)
            .setNeedAck(1)
            .setSeq(JsonMessages.seq())
            .setProductId(1)
            .setVersion(19)
            .setPayloadVer(1)
            .setDeviceSn(sn)
            .setDataLen(dataLen)
            .setPdata(ByteString.copyFrom(pdata))
        val packet = Delta3Proto.Delta3SendHeaderMsg.newBuilder().addMsg(header).build()
        return Outgoing(packet.toByteArray())
    }

    /** Empty header addressed app→app: the device answers with a full snapshot on get_reply. */
    fun snapshotRequest(): Outgoing {
        val header = Delta3Proto.Delta3Header.newBuilder()
            .setSrc(SRC_APP)
            .setDest(SRC_APP)
            .setSeq(JsonMessages.seq())
            .setFrom("Android")
        return Outgoing(Delta3Proto.Delta3SendHeaderMsg.newBuilder().addMsg(header).build().toByteArray())
    }

    /** Output state is not reported directly; flow_info == 14 means enabled, 4 means disabled. */
    fun deriveFlag(out: MutableMap<String, Any?>, flowKeys: List<String>, target: String) {
        val values = flowKeys.mapNotNull { (out[it] as? Number)?.toInt() }
        if (values.isEmpty()) return
        when {
            values.any { it == 14 } -> out[target] = 1
            values.all { it == 4 } -> out[target] = 0
        }
    }
}
