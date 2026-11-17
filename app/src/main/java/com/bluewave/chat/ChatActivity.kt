package com.bluewave.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
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
    }

    data class ChatMessage(
        val id: String,
        val text: String,
        val time: String,
        val isSentByMe: Boolean,
        val mediaType: String = "TEXT", // TEXT, IMAGE, AUDIO, VIDEO
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            Toast.makeText(this, "Permissions granted! Tap the button again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Camera/Microphone permission needed for media", Toast.LENGTH_SHORT).show()
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

        val backButton = findViewById<TextView>(R.id.backButton)
        val chatPeerName = findViewById<TextView>(R.id.chatPeerName)
        chatPeerStatus = findViewById(R.id.chatPeerStatus)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        val sendButton = findViewById<Button>(R.id.sendButton)
        val cameraButton = findViewById<TextView>(R.id.cameraButton)
        val attachButton = findViewById<TextView>(R.id.attachButton)

        val socket = activeSocket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Bluetooth connection lost", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        chatPeerName.text = socket.remoteDevice.name ?: "BlueWave Peer"
        chatPeerStatus.text = "Active (Nearby Offline)"

        chatAdapter = ChatAdapter(messages)
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesRecyclerView.adapter = chatAdapter

        backButton.setOnClickListener {
            closeConnectionAndExit()
        }

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextPacket(text)
                messageInput.setText("")
            }
        }

        cameraButton.setOnClickListener {
            launchCameraCapture()
        }

        attachButton.setOnClickListener {
            if (isRecordingAudio) {
                stopAndSendVoiceNote()
            } else {
                showAttachmentOptions()
            }
        }

        setupStreamsAndListen(socket)
    }

    private fun setupStreamsAndListen(socket: BluetoothSocket) {
        try {
            writer = PrintWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)
        } catch (e: IOException) {
            handleDisconnect()
            return
        }

        readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (isConnected) {
                    val line = reader.readLine() ?: break
                    processIncomingPacket(line)
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
        val time = currentTimeString()
        val safeText = text.replace("\n", "\\n")
        sendRawLine("TEXT|$msgId|$safeText")

        messages.add(ChatMessage(msgId, text, time, isSentByMe = true, mediaType = "TEXT"))
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
            "🎬  Send Video Clip",
            "🎤  Record Voice Note"
        )
        AlertDialog.Builder(this)
            .setTitle("Share Offline via Bluetooth")
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
            Toast.makeText(this, "Could not load photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCompressedImageFile(sourceFile: File) {
        Thread {
            try {
                val original = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return@Thread
                val maxDim = 720
                val ratio = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
                val scaled = Bitmap.createScaledBitmap(
                    original,
                    (original.width * ratio).toInt().coerceAtLeast(1),
                    (original.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                val bytes = baos.toByteArray()

                val savedFile = File(cacheDir, "sent_img_${System.currentTimeMillis()}.jpg")
                FileOutputStream(savedFile).use { it.write(bytes) }

                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val msgId = UUID.randomUUID().toString()
                sendRawLine("MEDIA|$msgId|IMAGE|photo.jpg|$base64")

                runOnUiThread {
                    messages.add(
                        ChatMessage(
                            id = msgId,
                            text = "Photo",
                            time = currentTimeString(),
                            isSentByMe = true,
                            mediaType = "IMAGE",
                            localFilePath = savedFile.absolutePath
                        )
                    )
                    refreshChatList()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to send photo", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handlePickedVideoUri(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@Thread
                val bytes = inputStream.readBytes()
                if (bytes.size > 6 * 1024 * 1024) {
                    runOnUiThread {
                        Toast.makeText(this, "Please select a short video under 6 MB for Bluetooth", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val savedFile = File(cacheDir, "sent_vid_${System.currentTimeMillis()}.mp4")
                FileOutputStream(savedFile).use { it.write(bytes) }

                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val msgId = UUID.randomUUID().toString()
                sendRawLine("MEDIA|$msgId|VIDEO|video.mp4|$base64")

                runOnUiThread {
                    messages.add(
                        ChatMessage(
                            id = msgId,
                            text = "Video Clip",
                            time = currentTimeString(),
                            isSentByMe = true,
                            mediaType = "VIDEO",
                            localFilePath = savedFile.absolutePath
                        )
                    )
                    refreshChatList()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to send video", Toast.LENGTH_SHORT).show()
                }
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

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
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
            chatPeerStatus.text = "🎤 Recording voice note... Tap 📎 to send"
            Toast.makeText(this, "Recording... Tap 📎 again to send voice note", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            isRecordingAudio = false
            Toast.makeText(this, "Could not start microphone", Toast.LENGTH_SHORT).show()
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
        chatPeerStatus.text = "Active (Nearby Offline)"

        val file = audioFile ?: return
        if (!file.exists()) return

        Thread {
            try {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val msgId = UUID.randomUUID().toString()
                sendRawLine("MEDIA|$msgId|AUDIO|voice.m4a|$base64")

                runOnUiThread {
                    messages.add(
                        ChatMessage(
                            id = msgId,
                            text = "Voice Note",
                            time = currentTimeString(),
                            isSentByMe = true,
                            mediaType = "AUDIO",
                            localFilePath = file.absolutePath
                        )
                    )
                    refreshChatList()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun processIncomingPacket(line: String) {
        val parts = line.split("|", limit = 5)
        if (parts.isEmpty()) return

        when (parts[0]) {
            "TEXT" -> if (parts.size >= 3) {
                val msgId = parts[1]
                val text = parts[2].replace("\\n", "\n")
                runOnUiThread {
                    messages.add(ChatMessage(msgId, text, currentTimeString(), isSentByMe = false, mediaType = "TEXT"))
                    refreshChatList()
                }
            }
            "DEL" -> if (parts.size >= 2) {
                val msgId = parts[1]
                runOnUiThread { removeMessageById(msgId) }
            }
            "MEDIA" -> if (parts.size >= 5) {
                val msgId = parts[1]
                val mediaType = parts[2]
                val fileName = parts[3]
                val base64 = parts[4]
                try {
                    val bytes = Base64.decode(base64, Base64.NO_WRAP)
                    val ext = when (mediaType) {
                        "IMAGE" -> "jpg"
                        "AUDIO" -> "m4a"
                        "VIDEO" -> "mp4"
                        else -> fileName.substringAfterLast('.', "bin")
                    }
                    val savedFile = File(cacheDir, "recv_${System.currentTimeMillis()}.$ext")
                    FileOutputStream(savedFile).use { it.write(bytes) }

                    val label = when (mediaType) {
                        "IMAGE" -> "Photo"
                        "AUDIO" -> "Voice Note"
                        "VIDEO" -> "Video Clip"
                        else -> "Attachment"
                    }
                    runOnUiThread {
                        messages.add(
                            ChatMessage(
                                id = msgId,
                                text = label,
                                time = currentTimeString(),
                                isSentByMe = false,
                                mediaType = mediaType,
                                localFilePath = savedFile.absolutePath
                            )
                        )
                        refreshChatList()
                    }
                } catch (_: Exception) {
                }
            }
        }
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

    private fun currentTimeString(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun playOrOpenMedia(msg: ChatMessage) {
        val path = msg.localFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Media file unavailable", Toast.LENGTH_SHORT).show()
            return
        }

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
                Toast.makeText(this, "Cannot play voice note", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val mime = if (msg.mediaType == "VIDEO") "video/*" else "image/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "No app found to open this media", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleDisconnect() {
        if (!isConnected) return
        isConnected = false
        chatPeerStatus.text = "Disconnected"
        Toast.makeText(this, "Peer disconnected from Bluetooth", Toast.LENGTH_SHORT).show()
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
        isConnected = false
        try {
            audioRecorder?.release()
            activeMediaPlayer?.release()
            activeSocket?.close()
        } catch (_: Exception) {
        }
        activeSocket = null
    }

    inner class ChatAdapter(
        private val items: List<ChatMessage>
    ) : RecyclerView.Adapter<ChatAdapter.MsgViewHolder>() {

        inner class MsgViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
            val bubbleLayout: LinearLayout = view.findViewById(R.id.bubbleLayout)
            val messageImage: ImageView = view.findViewById(R.id.messageImage)
            val mediaAttachmentPill: TextView = view.findViewById(R.id.mediaAttachmentPill)
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
            holder.bubbleLayout.setBackgroundResource(
                if (msg.isSentByMe) R.drawable.bg_sent_pastel else R.drawable.bg_white_pill
            )

            holder.messageImage.visibility = View.GONE
            holder.mediaAttachmentPill.visibility = View.GONE
            holder.messageText.visibility = View.VISIBLE

            when (msg.mediaType) {
                "IMAGE" -> {
                    holder.messageImage.visibility = View.VISIBLE
                    holder.messageText.visibility = View.GONE
                    msg.localFilePath?.let { path ->
                        val bmp = BitmapFactory.decodeFile(path)
                        holder.messageImage.setImageBitmap(bmp)
                    }
                }
                "AUDIO" -> {
                    holder.mediaAttachmentPill.visibility = View.VISIBLE
                    holder.mediaAttachmentPill.text = "▶  Voice Note (Tap to Play)"
                    holder.messageText.visibility = View.GONE
                }
                "VIDEO" -> {
                    holder.mediaAttachmentPill.visibility = View.VISIBLE
                    holder.mediaAttachmentPill.text = "🎬  Video Clip (Tap to Watch)"
                    holder.messageText.visibility = View.GONE
                }
                else -> {
                    holder.messageText.text = msg.text
                }
            }

            holder.messageTime.text = msg.time

            holder.bubbleLayout.setOnClickListener {
                if (msg.mediaType != "TEXT") {
                    playOrOpenMedia(msg)
                }
            }

            holder.bubbleLayout.setOnLongClickListener {
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Delete Message?")
                    .setMessage("Remove this message from both phones?")
                    .setPositiveButton("Delete for Both") { _, _ ->
                        sendDeletePacket(msg.id)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
