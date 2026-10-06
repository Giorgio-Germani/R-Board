package app.reventor.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class Rcs1Test {

    private val id = ByteArray(16) { it.toByte() }
    private val id2 = ByteArray(16) { (it * 3).toByte() }

    @Test
    fun helloRoundtrip() {
        val payload = Rcs1.helloPayload(id, Rcs1.FLAG_PUSH_SUPPORTED, "windows-pc")
        val hello = Rcs1.parseHello(payload)
        assertEquals(Rcs1.PROTO_VERSION, hello.protoVersion)
        assertArrayEquals(id, hello.deviceId)
        assertEquals(Rcs1.FLAG_PUSH_SUPPORTED, hello.flags)
        assertEquals("windows-pc", hello.name)
    }

    @Test
    fun clipStartRoundtrip() {
        val hash = Rcs1.sha256First16("hello world")
        val payload = Rcs1.clipStartPayload(hash, id2, 1728190000123L, true, 4242, Rcs1.MIME_TEXT)
        val clip = Rcs1.parseClipStart(payload)
        assertArrayEquals(hash, clip.hash)
        assertArrayEquals(id2, clip.originId)
        assertEquals(1728190000123L, clip.timestampMs)
        assertTrue(clip.sensitive)
        assertEquals(4242L, clip.totalLen)
        assertEquals(Rcs1.MIME_TEXT, clip.mime)
    }

    @Test
    fun clipChunkRoundtrip() {
        val hash = Rcs1.sha256First16("x")
        val data = ByteArray(Rcs1.CHUNK_SIZE) { (it % 251).toByte() }
        val chunk = Rcs1.parseClipChunk(Rcs1.clipChunkPayload(hash, 12345, data))
        assertArrayEquals(hash, chunk.hash)
        assertEquals(12345, chunk.seq)
        assertArrayEquals(data, chunk.data)
    }

    @Test
    fun frameRoundtripMultiChunk() {
        val out = ByteArrayOutputStream()
        val text = "REVENTOR — äöü ß test 🎉 ".repeat(500)
        val hash = Rcs1.sha256First16(text)
        val bytes = text.toByteArray(Charsets.UTF_8)
        Rcs1.writeFrame(out, Rcs1.TYPE_CLIP_START, Rcs1.clipStartPayload(hash, id, 1L, false, bytes.size.toLong(), Rcs1.MIME_TEXT))
        var seq = 0
        var off = 0
        while (off < bytes.size) {
            val len = minOf(Rcs1.CHUNK_SIZE, bytes.size - off)
            Rcs1.writeFrame(out, Rcs1.TYPE_CLIP_CHUNK, Rcs1.clipChunkPayload(hash, seq++, bytes.copyOfRange(off, off + len)))
            off += len
        }

        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val assembled = java.io.ByteArrayOutputStream()
        var start: Rcs1.ClipStart? = null
        while (true) {
            val frame = Rcs1.readFrame(input) ?: break
            when (frame.type) {
                Rcs1.TYPE_CLIP_START -> start = Rcs1.parseClipStart(frame.payload)
                Rcs1.TYPE_CLIP_CHUNK -> {
                    val chunk = Rcs1.parseClipChunk(frame.payload)
                    assembled.write(chunk.data)
                }
            }
        }
        assertArrayEquals(hash, start!!.hash)
        assertEquals(bytes.size.toLong(), start.totalLen)
        assertEquals(text, assembled.toString("UTF-8"))
    }

    @Test
    fun readFrameReturnsNullOnEof() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        assertEquals(null, Rcs1.readFrame(input))
    }

    @Test
    fun sha256First16IsStableAndDiscriminating() {
        assertFalse(Rcs1.sha256First16("a").contentEquals(Rcs1.sha256First16("b")))
        assertArrayEquals(Rcs1.sha256First16("deterministic"), Rcs1.sha256First16("deterministic"))
    }
}
