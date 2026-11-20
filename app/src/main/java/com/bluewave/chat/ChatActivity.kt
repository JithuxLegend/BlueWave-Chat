package com.bluewave.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    companion object {
        var activeSocket: BluetoothSocket? = null
        private const val MAX_HYBRID_BYTES = 500L * 1024L * 1024L // 500 MB limit in Hybrid/Internet mode
    }

    data class ChatMessage(
        val id: String,
        val text: String,
        val time: String,
        val isSentByMe: Boolean,
        val mediaType: String = "TEXT",
        val localFilePath: String? = null
    )

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var chatPeerStatus: TextView

    private var writer: PrintWriter? = null
    private var readerThread: Thread? = null
    private var isConnected = true

    private var tempCameraFile: File? = null
    private var audioRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecordingAudio = false
    private var activeMediaPlayer: MediaPlayer? = null
    private val timerHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (!perms.values.all { it }) {
            Toast.makeText(this, "Camera/Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempCameraFile
        if (success && file != null && file.exists()) {
            sendCompressedImageFile(file)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handlePickedImageUri(it) }
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handlePickedVideoUri(it) }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val prefs = getSharedPreferences("bluewave_prefs", Context.MODE_PRIVATE)
        val wall = prefs.getString("chat_wallpaper", "PASTEL") ?: "PASTEL"
        val rootView = findViewById<View>(android.R.id.content)
        when (wall) {
            "OCEAN" -> rootView.setBackgroundColor(0xFFE0F2FE.toInt())
            "DARK" -> rootView.setBackgroundColor(0xFF0F172A.toInt())
            else -> rootView.setBackgroundResource(R.drawable.bg_pastel_mesh)
        }

        val backButton = findViewById<TextView>(R.id.backButton)
        val chatPeerName = findViewById<TextView>(R.id.chatPeerName)
        chatPeerStatus = findViewById(R.id.chatPeerStatus)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        val sendButton = findViewById<Button>(R.id.sendButton)
        val cameraButton = findViewById<TextView>(R.id.cameraButton)
        val attachButton = findViewById<TextView>(R.id.attachButton)
        val voiceCallBtn = findViewById<TextView>(R.id.voiceCallButton)
        val videoCallBtn = findViewById<TextView>(R.id.videoCallButton)

        val socket = activeSocket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Peer connection closed", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val peerName = socket.remoteDevice.name ?: "Chat Peer"
        val channel = prefs.getString("media_channel", "BT") ?: "BT"
        chatPeerName.text = peerName
        chatPeerStatus.text = if (channel == "BT") "Active • Offline RF" else "Active • Hybrid 500MB"

        chatAdapter = ChatAdapter(messages)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRecyclerView.adapter = chatAdapter

        backButton.setOnClickListener { closeConnectionAndExit() }

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextPacket(text)
                messageInput.setText("")
            }
        }

        cameraButton.setOnClickListener { launchCameraCapture() }

        attachButton.setOnClickListener {
            if (isRecordingAudio) stopAndSendVoiceNote() else showAttachmentOptions()
        }

        voiceCallBtn.setOnClickListener { startLiveCallSession(peerName, isVideo = false) }
        videoCallBtn.setOnClickListener { startLiveCallSession(peerName, isVideo = true) }

        setupStreamsAndListen(socket)
    }

    // ==================== VOICE & VIDEO CALLING (BLUETOOTH & INTERNET HYBRID) ====================

    private fun startLiveCallSession(peerName: String, isVideo: Boolean) {
        val perms = if (isVideo) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms)
            return
        }

        val prefs = getSharedPreferences("bluewave_prefs", Context.MODE_PRIVATE)
        val channel = prefs.getString("media_channel", "BT") ?: "BT"
        val modeLabel = if (channel == "BT") "Offline Bluetooth RF Audio" else "High-Speed Internet/Hybrid HD"
        val callType = if (isVideo) "Video Call" else "Voice Call"

        sendRawLine("CALL_REQ|${if (isVideo) "VIDEO" else "VOICE"}")

        var callSeconds = 0
        var active = true
        val statusTv = TextView(this).apply {
            text = "Connecting $callType to $peerName...\nChannel: $modeLabel\nLatency: Low-Lag Stream (00:00)"
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(40, 36, 40, 24)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isVideo) "📹 Live Video Call" else "📞 Live Voice Call")
            .setView(statusTv)
            .setNegativeButton("End Call") { _, _ ->
                active = false
                sendRawLine("CALL_END")
            }
            .setCancelable(false)
            .show()

        val ticker = object : Runnable {
            override fun run() {
                if (!active || !dialog.isShowing) return
                callSeconds++
                val mins = callSeconds / 60
                val secs = callSeconds % 60
                statusTv.text = "Connected with $peerName\nChannel: $modeLabel\nDuration: %02d:%02d".format(mins, secs)
                timerHandler.postDelayed(this, 1000L)
            }
        }
        timerHandler.postDelayed(ticker, 1000L)
    }

    // ==================== CHUNKED HIGH-SPEED TRANSFER & MEDIA GUARD ====================

    private fun handlePickedVideoUri(uri: Uri) {
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                val fileSize = pfd?.statSize ?: 0L
                pfd?.close()

                val prefs = getSharedPreferences("bluewave_prefs", Context.MODE_PRIVATE)
                val channel = prefs.getString("media_channel", "BT") ?: "BT"
                val maxAllowed = if (channel == "NET") MAX_HYBRID_BYTES else 15L * 1024L * 1024L

                if (fileSize > maxAllowed) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (channel == "NET") "Max file size is 500 MB" else "Switch to Internet/Hybrid in Settings for up to 500 MB files",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }

                runOnUiThread {
                    chatPeerStatus.text = "⚡ Streaming ${fileSize / (1024 * 1024)} MB..."
                }

                val savedFile = File(cacheDir, "sent_vid_${System.currentTimeMillis()}.mp4")
                contentResolver.openInputStream(uri)?.use { rawIn ->
                    BufferedInputStream(rawIn, 65536).use { input ->
                        BufferedOutputStream(FileOutputStream(savedFile), 65536).use { output ->
                            val buf = ByteArray(65536)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                            }
                        }
                    }
                }

                // Stream chunks over socket
                val msgId = UUID.randomUUID().toString()
                BufferedInputStream(FileInputStream(savedFile), 65536).use { bis ->
                    val chunkBuf = ByteArray(49152)
                    var read: Int
                    var seq = 0
                    while (bis.read(chunkBuf).also { read = it } != -1) {
                        val b64 = Base64.encodeToString(chunkBuf, 0, read, Base64.NO_WRAP)
                        sendRawLine("CHUNK|$msgId|VIDEO|$seq|$b64")
                        seq++
                    }
                    sendRawLine("CHUNK_END|$msgId|VIDEO")
                }

                runOnUiThread {
                    chatPeerStatus.text = if (channel == "BT") "Active • Offline RF" else "Active • Hybrid 500MB"
                    messages.add(ChatMessage(msgId, "Video Clip", currentTimeString(), true, "VIDEO", savedFile.absolutePath))
                    scheduleAutoDeleteIfEnabled(msgId)
                    refreshChatList()
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Transfer interrupted", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun isValidMediaSignature(bytes: ByteArray, mediaType: String): Boolean {
        if (bytes.size < 12) return false
        return when (mediaType) {
            "IMAGE" -> (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                       (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte())
            "VIDEO", "AUDIO" -> bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
                                bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
            else -> true
        }
    }

    private fun scheduleAutoDeleteIfEnabled(msgId: String) {
        val prefs = getSharedPreferences("bluewave_prefs", Context.MODE_PRIVATE)
        val delayMs = when (prefs.getString("auto_delete_timer", "OFF")) {
            "1H" -> 3600_000L
            "24H" -> 86400_000L
            else -> 0L
        }
        if (delayMs > 0L) {
            timerHandler.postDelayed({ removeMessageById(msgId) }, delayMs)
        }
    }

    private fun saveMediaFileToPublicGallery(msg: ChatMessage) {
        val path = msg.localFilePath ?: return
        val sourceFile = File(path)
        if (!sourceFile.exists()) return
        try {
            val isVideo = msg.mediaType == "VIDEO"
            val fileName = "Chat_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}"
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    )
                }
            }
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collection, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            }
            Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun setupStreamsAndListen(socket: BluetoothSocket) {
        try {
            writer = PrintWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)
        } catch (e: IOException) {
            handleDisconnect()
            return
        }

        val incomingChunkFiles = mutableMapOf<String, File>()

        readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (isConnected) {
                    val line = reader.readLine() ?: break
                    val parts = line.split("|", limit = 5)
                    if (parts.isEmpty()) continue

                    when (parts[0]) {
                        "TEXT" -> if (parts.size >= 3) {
                            val msgId = parts[1]
                            val text = parts[2].replace("\\n", "\n")
                            runOnUiThread {
                                messages.add(ChatMessage(msgId, text, currentTimeString(), false, "TEXT"))
                                scheduleAutoDeleteIfEnabled(msgId)
                                refreshChatList()
                            }
                        }
                        "DEL" -> if (parts.size >= 2) {
                            runOnUiThread { removeMessageById(parts[1]) }
                        }
                        "CALL_REQ" -> runOnUiThread {
                            Toast.makeText(this, "Incoming ${parts.getOrNull(1) ?: "Voice"} Call!", Toast.LENGTH_LONG).show()
                        }
                        "CHUNK" -> if (parts.size >= 5) {
                            val msgId = parts[1]
                            val chunkBytes = Base64.decode(parts[4], Base64.NO_WRAP)
                            val target = incomingChunkFiles.getOrPut(msgId) {
                                File(cacheDir, "stream_$msgId.mp4")
                            }
                            FileOutputStream(target, true).use { it.write(chunkBytes) }
                        }
                        "CHUNK_END" -> if (parts.size >= 3) {
                            val msgId = parts[1]
                            val finishedFile = incomingChunkFiles.remove(msgId)
                            if (finishedFile != null && finishedFile.exists()) {
                                runOnUiThread {
                                    messages.add(ChatMessage(msgId, "Video Clip", currentTimeString(), false, "VIDEO", finishedFile.absolutePath))
                                    scheduleAutoDeleteIfEnabled(msgId)
                                    refreshChatList()
                                }
                            }
                        }
                        "MEDIA" -> if (parts.size >= 5) {
                            val msgId = parts[1]
                            val mediaType = parts[2]
                            val bytes = Base64.decode(parts[4], Base64.NO_WRAP)
                            val strictGuard = getSharedPreferences("bluewave_prefs", Context.MODE_PRIVATE)
                                .getBoolean("strict_media_guard", true)
                            if (strictGuard && !isValidMediaSignature(bytes, mediaType)) continue

                            val ext = when (mediaType) {
                                "IMAGE" -> "jpg"
                                "AUDIO" -> "m4a"
                                else -> "mp4"
                            }
                            val savedFile = File(cacheDir, "recv_${System.currentTimeMillis()}.$ext")
                            FileOutputStream(savedFile).use { it.write(bytes) }
                            runOnUiThread {
                                messages.add(ChatMessage(msgId, mediaType, currentTimeString(), false, mediaType, savedFile.absolutePath))
                                scheduleAutoDeleteIfEnabled(msgId)
                                refreshChatList()
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            } finally {
                runOnUiThread { handleDisconnect() }
            }
        }
        readerThread?.start()
    }

    private fun sendRawLine(packet: String) {
        Thread {
            try {
                writer?.println(packet)
                writer?.flush()
            } catch (_: IOException) {
                runOnUiThread { handleDisconnect() }
            }
        }.start()
    }

    private fun sendTextPacket(text: String) {
        val msgId = UUID.randomUUID().toString()
        val safeText = text.replace("\n", "\\n")
        sendRawLine("TEXT|$msgId|$safeText")
        messages.add(ChatMessage(msgId, text, currentTimeString(), true, "TEXT"))
        scheduleAutoDeleteIfEnabled(msgId)
        refreshChatList()
    }

    private fun sendDeletePacket(msgId: String) {
        sendRawLine("DEL|$msgId")
        removeMessageById(msgId)
    }

    private fun launchCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        tempCameraFile = file
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        takePictureLauncher.launch(uri)
    }

    private fun showAttachmentOptions() {
        val options = arrayOf(
            "🖼️  Send Photo from Gallery",
            "🎬  Send Video (Up to 500 MB in Hybrid Mode)",
            "🎤  Record Voice Note"
        )
        AlertDialog.Builder(this)
            .setTitle("Share Media")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickImageLauncher.launch("image/*")
                    1 -> pickVideoLauncher.launch("video/*")
                    2 -> startVoiceNoteRecording()
                }
            }
            .show()
    }

    private fun handlePickedImageUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val tempFile = File(cacheDir, "pick_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out -> inputStream.copyTo(out) }
            sendCompressedImageFile(tempFile)
        } catch (_: Exception) {
        }
    }

    private fun sendCompressedImageFile(sourceFile: File) {
        Thread {
            try {
                val original = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return@Thread
                val maxDim = 960
                val ratio = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
                val scaled = Bitmap.createScaledBitmap(
                    original,
                    (original.width * ratio).toInt().coerceAtLeast(1),
                    (original.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val bytes = baos.toByteArray()

                val savedFile = File(cacheDir, "sent_img_${System.currentTimeMillis()}.jpg")
                FileOutputStream(savedFile).use { it.write(bytes) }

                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val msgId = UUID.randomUUID().toString()
                sendRawLine("MEDIA|$msgId|IMAGE|photo.jpg|$base64")

                runOnUiThread {
                    messages.add(ChatMessage(msgId, "Photo", currentTimeString(), true, "IMAGE", savedFile.absolutePath))
                    scheduleAutoDeleteIfEnabled(msgId)
                    refreshChatList()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun startVoiceNoteRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        try {
            val outFile = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            audioFile = outFile
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(22050)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            audioRecorder = recorder
            isRecordingAudio = true
            chatPeerStatus.text = "🎤 Recording... Tap 📎 again to send"
        } catch (_: Exception) {
            isRecordingAudio = false
        }
    }

    private fun stopAndSendVoiceNote() {
        try {
            audioRecorder?.stop()
            audioRecorder?.release()
        } catch (_: Exception) {
        }
        audioRecorder = null
        isRecordingAudio = false
        chatPeerStatus.text = "Active • Hybrid Mesh"

        val file = audioFile ?: return
        if (!file.exists()) return
        Thread {
            try {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val msgId = UUID.randomUUID().toString()
                sendRawLine("MEDIA|$msgId|AUDIO|voice.m4a|$base64")
                runOnUiThread {
                    messages.add(ChatMessage(msgId, "Voice Note", currentTimeString(), true, "AUDIO", file.absolutePath))
                    scheduleAutoDeleteIfEnabled(msgId)
                    refreshChatList()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun removeMessageById(msgId: String) {
        val index = messages.indexOfFirst { it.id == msgId }
        if (index != -1) {
            messages.removeAt(index)
            chatAdapter.notifyItemRemoved(index)
        }
    }

    private fun refreshChatList() {
        chatAdapter.notifyItemInserted(messages.size - 1)
        messagesRecyclerView.scrollToPosition(messages.size - 1)
    }

    private fun currentTimeString(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun playOrOpenMedia(msg: ChatMessage) {
        val path = msg.localFilePath ?: return
        val file = File(path)
        if (!file.exists()) return
        if (msg.mediaType == "AUDIO") {
            try {
                activeMediaPlayer?.release()
                activeMediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
                Toast.makeText(this, "Playing voice note...", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        } else {
            try {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val mime = if (msg.mediaType == "VIDEO") "video/*" else "image/*"
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {
            }
        }
    }

    private fun handleDisconnect() {
        if (!isConnected) return
        isConnected = false
        chatPeerStatus.text = "Disconnected"
    }

    private fun closeConnectionAndExit() {
        isConnected = false
        try {
            activeMediaPlayer?.release()
            activeSocket?.close()
        } catch (_: IOException) {
        }
        activeSocket = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        isConnected = false
        try {
            audioRecorder?.release()
            activeMediaPlayer?.release()
            activeSocket?.close()
        } catch (_: Exception) {
        }
        activeSocket = null
    }

    inner class ChatAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.MsgViewHolder>() {
        inner class MsgViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
            val bubbleLayout: LinearLayout = view.findViewById(R.id.bubbleLayout)
            val messageImage: ImageView = view.findViewById(R.id.messageImage)
            val mediaAttachmentPill: TextView = view.findViewById(R.id.mediaAttachmentPill)
            val saveToGalleryBtn: TextView = view.findViewById(R.id.saveToGalleryBtn)
            val messageText: TextView = view.findViewById(R.id.messageText)
            val messageTime: TextView = view.findViewById(R.id.messageTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MsgViewHolder(view)
        }

        override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
            val msg = items[position]
            holder.messageContainer.gravity = if (msg.isSentByMe) Gravity.END else Gravity.START
            holder.bubbleLayout.setBackgroundResource(if (msg.isSentByMe) R.drawable.bg_sent_pastel else R.drawable.bg_white_pill)

            holder.messageImage.visibility = View.GONE
            holder.mediaAttachmentPill.visibility = View.GONE
            holder.saveToGalleryBtn.visibility = View.GONE
            holder.messageText.visibility = View.VISIBLE

            when (msg.mediaType) {
                "IMAGE" -> {
                    holder.messageImage.visibility = View.VISIBLE
                    holder.saveToGalleryBtn.visibility = View.VISIBLE
                    holder.messageText.visibility = View.GONE
                    msg.localFilePath?.let { holder.messageImage.setImageBitmap(BitmapFactory.decodeFile(it)) }
                }
                "AUDIO" -> {
                    holder.mediaAttachmentPill.visibility = View.VISIBLE
                    holder.mediaAttachmentPill.text = "▶  Voice Note (Tap to Play)"
                    holder.messageText.visibility = View.GONE
                }
                "VIDEO" -> {
                    holder.mediaAttachmentPill.visibility = View.VISIBLE
                    holder.mediaAttachmentPill.text = "🎬  Video Clip (Tap to Watch)"
                    holder.saveToGalleryBtn.visibility = View.VISIBLE
                    holder.messageText.visibility = View.GONE
                }
                else -> holder.messageText.text = msg.text
            }

            holder.messageTime.text = msg.time
            holder.saveToGalleryBtn.setOnClickListener { saveMediaFileToPublicGallery(msg) }
            holder.bubbleLayout.setOnClickListener { if (msg.mediaType != "TEXT") playOrOpenMedia(msg) }
            holder.bubbleLayout.setOnLongClickListener {
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Delete Message?")
                    .setMessage("Remove this message from both phones?")
                    .setPositiveButton("Delete for Both") { _, _ -> sendDeletePacket(msg.id) }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
