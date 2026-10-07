package app.reventor.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID

/**
 * Foreground service (type connectedDevice) that listens for desktop agents over
 * Bluetooth Classic RFCOMM and syncs the system clipboard in both directions.
 * Runs in the keyboard app's process, so its UID carries the default-IME
 * clipboard read privilege — this is what makes proactive phone→desktop push
 * possible without user interaction.
 */
class SyncService : Service() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("8c1f9a52-6e3b-4c7a-9d4e-2b1f0a7c5d31")
        const val ACTION_START = "app.reventor.sync.START"
        const val ACTION_STOP = "app.reventor.sync.STOP"
        private const val TAG = "ReventorSync"
        private const val CHANNEL_ID = "clipboard_sync"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 800L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    private val btAdapter get() = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val clipboard get() = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // API 34+ requires BLUETOOTH_CONNECT to be granted *before* an FGS of type
        // connectedDevice calls startForeground — check first, never crash.
        val connectPermission =
            if (Build.VERSION.SDK_INT >= 31) ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            else PackageManager.PERMISSION_GRANTED
        if (connectPermission != PackageManager.PERMISSION_GRANTED) {
            SyncState.statusLine = "Bluetooth permission missing — grant it in R-Board Clipboard Sync setup"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground rejected", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (acceptJob?.isActive != true) {
            acceptJob = scope.launch { acceptLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        acceptJob?.cancel()
        scope.cancel()
        SyncState.serviceRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clipboard sync", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SyncSetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("R-Board Clipboard Sync")
            .setContentText(SyncState.statusLine)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        SyncState.serviceRunning = true
    }

    private suspend fun acceptLoop(): Unit = coroutineScope {
        SyncState.statusLine = "Waiting for desktop…"
        var busy = false
        while (isActive) {
            val adapter = btAdapter
            if (adapter == null || !adapter.isEnabled) {
                delay(3000)
                continue
            }
            val server = try {
                adapter.listenUsingRfcommWithServiceRecord("R-Board Clipboard Sync", SERVICE_UUID)
            } catch (e: SecurityException) {
                // transient (e.g. BT just re-enabled) — retry, never stop advertising
                Log.w(TAG, "listen blocked by permission", e)
                delay(3000)
                continue
            } catch (e: IOException) {
                delay(3000)
                continue
            }
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                try { server.close() } catch (_: IOException) {}
                continue
            }
            // keep the listening socket open: the SDP record must stay advertised
            // while a client is being served, otherwise a second computer cannot
            // even discover the phone
            if (busy) {
                // one session at a time — reject instead of silently stealing
                try { socket.close() } catch (_: IOException) {}
                continue
            }
            busy = true
            launch {
                try {
                    serve(socket)
                } finally {
                    busy = false
                }
            }
        }
    }

    private suspend fun serve(socket: BluetoothSocket) {
        val peerName = try { socket.remoteDevice.name } catch (_: SecurityException) { null }
        val peerLabel = peerName ?: socket.remoteDevice.address
        SyncState.connectedPeer = peerLabel
        SyncState.statusLine = "Connected: $peerLabel"
        val session = Session(
            this,
            DataOutputStream(BufferedOutputStream(socket.outputStream)),
            SyncPrefs.deviceId(this)
        )
        try {
            coroutineScope {
                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                Rcs1.writeFrame(
                    session.out, Rcs1.TYPE_HELLO,
                    Rcs1.helloPayload(session.ownId, Rcs1.FLAG_PUSH_SUPPORTED, "REVENTOR Keyboard")
                )
                pushCurrentClipboard(session)
                launch { pollClipboard(session, socket) }
                while (true) {
                    val frame = Rcs1.readFrame(input) ?: break
                    session.handleFrame(frame)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "connection ended", e)
        } finally {
            SyncState.connectedPeer = null
            SyncState.statusLine = "Waiting for desktop…"
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** Current system clipboard as (text, sensitive), or null when unreadable/empty. */
    private fun currentClipText(): Pair<String, Boolean>? {
        return try {
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return null
            if (text.isEmpty()) return null
            val sensitive = Build.VERSION.SDK_INT >= 26 && (
                clip.description.getExtras()?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false
                )
            text to sensitive
        } catch (e: Exception) {
            Log.w(TAG, "clipboard read failed", e)
            null
        }
    }

    private suspend fun pushCurrentClipboard(session: Session) {
        if (!SyncPrefs.pushToDesktop(this)) return
        val clip = currentClipText() ?: return
        session.sendClip(clip.first, clip.second)
    }

    private suspend fun pollClipboard(session: Session, socket: BluetoothSocket) {
        while (currentCoroutineContext().isActive && socket.isConnected) {
            if (SyncPrefs.pushToDesktop(this@SyncService)) {
                currentClipText()?.let { (text, sensitive) -> session.sendClip(text, sensitive) }
            }
            delay(POLL_INTERVAL_MS)
        }
    }
}

/** Ring of recent clip hashes for echo suppression (protocol spec, §Loop/echo suppression). */
internal class ClipHistory {
    private class Entry(val hash: ByteArray, val at: Long)
    private val entries = ArrayDeque<Entry>()

    fun seenRecently(hash: ByteArray): Boolean {
        prune()
        return entries.any { it.hash.contentEquals(hash) }
    }

    fun record(hash: ByteArray) {
        prune()
        entries.addLast(Entry(hash, System.currentTimeMillis()))
        if (entries.size > 64) entries.removeFirst()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 120_000
        while (entries.isNotEmpty() && entries.first().at < cutoff) entries.removeFirst()
    }
}

/** One connected peer: frame handling, chunk assembly, echo suppression, clipboard writes. */
internal class Session(
    private val context: Context,
    val out: DataOutputStream,
    val ownId: ByteArray,
) {
    private val sendMutex = Mutex()
    private val history = ClipHistory()
    private var incoming: Incoming? = null

    private class Incoming(val meta: Rcs1.ClipStart) {
        val buf = ByteArrayOutputStream()
        var received = 0L
    }

    suspend fun handleFrame(frame: Rcs1.Frame) {
        when (frame.type) {
            Rcs1.TYPE_HELLO -> Unit // peer hello carries nothing we need in v1
            Rcs1.TYPE_CLIP_START -> {
                val meta = Rcs1.parseClipStart(frame.payload)
                if (meta.totalLen == 0L) return
                incoming = Incoming(meta)
            }
            Rcs1.TYPE_CLIP_CHUNK -> {
                val chunk = Rcs1.parseClipChunk(frame.payload)
                val cur = incoming ?: return
                if (!cur.meta.hash.contentEquals(chunk.hash)) return
                cur.buf.write(chunk.data, 0, chunk.data.size)
                cur.received += chunk.data.size
                if (cur.received >= cur.meta.totalLen) {
                    val meta = cur.meta
                    val text = String(cur.buf.toByteArray(), Charsets.UTF_8)
                    incoming = null
                    applyClip(meta, text)
                }
            }
            Rcs1.TYPE_PING -> Rcs1.writeFrame(out, Rcs1.TYPE_PONG, frame.payload)
        }
    }

    suspend fun sendClip(text: String, sensitive: Boolean) {
        if (text.isEmpty()) return
        val hash = Rcs1.sha256First16(text)
        if (history.seenRecently(hash)) return
        history.record(hash)
        sendMutex.withLock {
            val bytes = text.toByteArray(Charsets.UTF_8)
            Rcs1.writeFrame(
                out, Rcs1.TYPE_CLIP_START,
                Rcs1.clipStartPayload(
                    hash, ownId, System.currentTimeMillis(), sensitive,
                    bytes.size.toLong(), Rcs1.MIME_TEXT
                )
            )
            var seq = 0
            var off = 0
            while (off < bytes.size) {
                val len = minOf(Rcs1.CHUNK_SIZE, bytes.size - off)
                Rcs1.writeFrame(
                    out, Rcs1.TYPE_CLIP_CHUNK,
                    Rcs1.clipChunkPayload(hash, seq, bytes.copyOfRange(off, off + len))
                )
                off += len
                seq++
            }
        }
    }

    private fun applyClip(meta: Rcs1.ClipStart, text: String) {
        if (meta.originId.contentEquals(ownId)) return
        if (!SyncPrefs.acceptFromDesktop(context)) return
        if (history.seenRecently(meta.hash)) return
        try {
            val cd = ClipData.newPlainText("reventor", text)
            if (meta.sensitive && Build.VERSION.SDK_INT >= 33) {
                cd.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(cd)
            history.record(meta.hash)
            Log.i("ReventorSync", "applied clip from desktop (${text.length} chars)")
        } catch (e: Exception) {
            Log.w("ReventorSync", "failed to apply clip", e)
        }
    }
}
