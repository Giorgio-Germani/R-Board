package app.reventor.sync

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * RCS-1 wire codec — see docs/protocol.md in the repository root.
 *
 * Frame = [u16 BE length incl. type byte][u8 type][payload].
 */
object Rcs1 {
    const val PROTO_VERSION = 1
    const val TYPE_HELLO = 0x01
    const val TYPE_CLIP_START = 0x10
    const val TYPE_CLIP_CHUNK = 0x11
    const val TYPE_PING = 0x20
    const val TYPE_PONG = 0x21
    const val FLAG_PUSH_SUPPORTED = 0x01
    const val FLAG_SENSITIVE = 0x01
    const val CHUNK_SIZE = 4096
    const val MIME_TEXT = "text/plain"
    const val MAX_FRAME = 0xFFFF

    class Frame(val type: Int, val payload: ByteArray)

    class Hello(val protoVersion: Int, val deviceId: ByteArray, val flags: Int, val name: String)

    class ClipStart(
        val hash: ByteArray,
        val originId: ByteArray,
        val timestampMs: Long,
        val sensitive: Boolean,
        val totalLen: Long,
        val mime: String,
    )

    class ClipChunk(val hash: ByteArray, val seq: Int, val data: ByteArray)

    fun sha256First16(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)

    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        if (payload.size + 1 > MAX_FRAME) throw IOException("frame too large")
        out.write(((payload.size + 1) ushr 8) and 0xFF)
        out.write((payload.size + 1) and 0xFF)
        out.write(type)
        out.write(payload)
        out.flush()
    }

    /** Returns null on clean EOF; throws IOException on malformed stream. */
    fun readFrame(input: DataInputStream): Frame? {
        val len = try {
            input.readUnsignedShort()
        } catch (e: EOFException) {
            return null
        }
        if (len < 1) throw IOException("empty frame")
        val type = input.readUnsignedByte()
        val payload = ByteArray(len - 1)
        input.readFully(payload)
        return Frame(type, payload)
    }

    fun helloPayload(deviceId: ByteArray, flags: Int, name: String): ByteArray {
        require(deviceId.size == 16) { "deviceId must be 16 bytes" }
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteArray(18 + nameBytes.size)
        buf[0] = PROTO_VERSION.toByte()
        System.arraycopy(deviceId, 0, buf, 1, 16)
        buf[17] = flags.toByte()
        System.arraycopy(nameBytes, 0, buf, 18, nameBytes.size)
        return buf
    }

    fun parseHello(p: ByteArray): Hello {
        require(p.size >= 18) { "hello too short" }
        return Hello(
            p[0].toInt() and 0xFF,
            p.copyOfRange(1, 17),
            p[17].toInt() and 0xFF,
            String(p, 18, p.size - 18, StandardCharsets.UTF_8),
        )
    }

    fun clipStartPayload(
        hash: ByteArray,
        originId: ByteArray,
        timestampMs: Long,
        sensitive: Boolean,
        totalLen: Long,
        mime: String,
    ): ByteArray {
        require(hash.size == 16 && originId.size == 16) { "ids must be 16 bytes" }
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        require(mimeBytes.size <= 0xFF) { "mime too long" }
        val buf = ByteArray(16 + 16 + 8 + 1 + 8 + 1 + mimeBytes.size)
        var o = 0
        System.arraycopy(hash, 0, buf, o, 16); o += 16
        System.arraycopy(originId, 0, buf, o, 16); o += 16
        for (i in 7 downTo 0) buf[o++] = (timestampMs ushr (8 * i)).toByte()
        buf[o++] = (if (sensitive) FLAG_SENSITIVE else 0).toByte()
        for (i in 7 downTo 0) buf[o++] = (totalLen ushr (8 * i)).toByte()
        buf[o++] = mimeBytes.size.toByte()
        System.arraycopy(mimeBytes, 0, buf, o, mimeBytes.size)
        return buf
    }

    fun parseClipStart(p: ByteArray): ClipStart {
        require(p.size >= 50) { "clip start too short" }
        var o = 0
        val hash = p.copyOfRange(o, o + 16); o += 16
        val originId = p.copyOfRange(o, o + 16); o += 16
        var ts = 0L; for (i in 0 until 8) ts = (ts shl 8) or (p[o++].toLong() and 0xFF)
        val flags = p[o++].toInt() and 0xFF
        var total = 0L; for (i in 0 until 8) total = (total shl 8) or (p[o++].toLong() and 0xFF)
        val mimeLen = p[o++].toInt() and 0xFF
        require(p.size >= o + mimeLen) { "mime truncated" }
        val mime = String(p, o, mimeLen, StandardCharsets.UTF_8)
        return ClipStart(hash, originId, ts, flags and FLAG_SENSITIVE != 0, total, mime)
    }

    fun clipChunkPayload(hash: ByteArray, seq: Int, data: ByteArray): ByteArray {
        require(hash.size == 16) { "hash must be 16 bytes" }
        require(data.size <= CHUNK_SIZE) { "chunk too large" }
        val buf = ByteArray(16 + 4 + data.size)
        System.arraycopy(hash, 0, buf, 0, 16)
        for (i in 0 until 4) buf[16 + i] = (seq ushr (8 * (3 - i))).toByte()
        System.arraycopy(data, 0, buf, 20, data.size)
        return buf
    }

    fun parseClipChunk(p: ByteArray): ClipChunk {
        require(p.size >= 20) { "chunk too short" }
        val hash = p.copyOfRange(0, 16)
        var seq = 0; for (i in 0 until 4) seq = (seq shl 8) or (p[16 + i].toInt() and 0xFF)
        return ClipChunk(hash, seq, p.copyOfRange(20, p.size))
    }
}
