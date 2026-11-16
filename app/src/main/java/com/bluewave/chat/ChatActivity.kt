package com.bluewave.chat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

data class ChatMessage(
    val id: String,
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: String
)

class ChatActivity : AppCompatActivity() {

    companion object {
        var activeSocket: BluetoothSocket? = null
    }

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: MessageAdapter
    private var outputStream: OutputStream? = null
    private var isRunning = true

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val socket = activeSocket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Bluetooth connection lost", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val peerName = socket.remoteDevice?.name ?: "BlueWave Peer"
        findViewById<TextView>(R.id.chatPeerName).text = peerName

        val listView = findViewById<ListView>(R.id.chatListView)
        chatAdapter = MessageAdapter(this, messages)
        listView.adapter = chatAdapter

        val inputMessage = findViewById<EditText>(R.id.inputMessage)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnVoiceCall = findViewById<Button>(R.id.btnVoiceCall)

        btnVoiceCall.setOnClickListener {
            Toast.makeText(this, "Live Voice Calling unlocks in Phase 5!", Toast.LENGTH_SHORT).show()
        }

        // Long-press any message to Delete for Both
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val targetMsg = messages[position]
            showDeleteDialog(targetMsg)
            true
        }

        try {
            outputStream = socket.outputStream
            startListeningForPackets(socket)
        } catch (e: Exception) {
            Toast.makeText(this, "Stream error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnSend.setOnClickListener {
            val rawText = inputMessage.text.toString().trim()
            if (rawText.isNotEmpty()) {
                sendTextMessage(rawText)
                inputMessage.text.clear()
            }
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    private fun sendTextMessage(text: String) {
        val msgId = UUID.randomUUID().toString().take(8)
        val safeText = text.replace("\n", "\\n")
        val packet = "MSG|$msgId|$safeText\n"

        Thread {
            try {
                outputStream?.write(packet.toByteArray(Charsets.UTF_8))
                outputStream?.flush()

                runOnUiThread {
                    messages.add(ChatMessage(msgId, text, isSentByMe = true, timestamp = getCurrentTime()))
                    chatAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to send. Connection dropped.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showDeleteDialog(message: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle("Delete Message?")
            .setMessage("Remove this message from both phones?")
            .setPositiveButton("Delete for Both") { _, _ ->
                deleteMessageForBoth(message.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteMessageForBoth(msgId: String) {
        // 1. Delete locally immediately
        messages.removeAll { it.id == msgId }
        chatAdapter.notifyDataSetChanged()

        // 2. Send invisible delete command over Bluetooth
        val deletePacket = "DEL|$msgId\n"
        Thread {
            try {
                outputStream?.write(deletePacket.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun startListeningForPackets(socket: BluetoothSocket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (isRunning) {
                    val line = reader.readLine() ?: break
                    val parts = line.split("|", limit = 3)
                    if (parts.size >= 2) {
                        val command = parts[0]
                        val msgId = parts[1]

                        when (command) {
                            "MSG" -> {
                                if (parts.size == 3) {
                                    val restoredText = parts[2].replace("\\n", "\n")
                                    runOnUiThread {
                                        messages.add(
                                            ChatMessage(
                                                id = msgId,
                                                text = restoredText,
                                                isSentByMe = false,
                                                timestamp = getCurrentTime()
                                            )
                                        )
                                        chatAdapter.notifyDataSetChanged()
                                    }
                                }
                            }
                            "DEL" -> {
                                runOnUiThread {
                                    val removed = messages.removeAll { it.id == msgId }
                                    if (removed) {
                                        chatAdapter.notifyDataSetChanged()
                                        Toast.makeText(this, "A message was deleted by peer", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.chatStatus).text = "Disconnected"
                        Toast.makeText(this, "Peer disconnected", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            activeSocket?.close()
            activeSocket = null
        } catch (_: Exception) {
        }
    }

    private class MessageAdapter(context: Context, private val items: List<ChatMessage>) :
        ArrayAdapter<ChatMessage>(context, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_message, parent, false)
            val msg = items[position]

            val container = view.findViewById<LinearLayout>(R.id.messageContainer)
            val bubble = view.findViewById<LinearLayout>(R.id.bubbleLayout)
            val textView = view.findViewById<TextView>(R.id.messageText)
            val timeView = view.findViewById<TextView>(R.id.messageTime)

            textView.text = msg.text
            timeView.text = msg.timestamp

            if (msg.isSentByMe) {
                container.gravity = Gravity.END
                bubble.setBackgroundResource(R.drawable.bg_bubble_sent)
                timeView.setTextColor(0xFFDBEAFE.toInt())
            } else {
                container.gravity = Gravity.START
                bubble.setBackgroundResource(R.drawable.bg_bubble_received)
                timeView.setTextColor(0xFF64748B.toInt())
            }

            return view
        }
    }
}
