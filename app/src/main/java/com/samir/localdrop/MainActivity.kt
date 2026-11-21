package com.samir.localdrop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.samir.localdrop.data.SharedRepository
import com.samir.localdrop.databinding.ActivityMainBinding
import com.samir.localdrop.databinding.ItemSharedBinding
import com.samir.localdrop.model.SharedItem
import java.io.File
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SharedRepository
    private var saveItem: SharedItem? = null
    private var serverCode = ""
    private var codeVisible = false
    private var lastServerRunning = false
    private var serverStartPending = false
    private var sendingText = false
    private var clearAllInProgress = false
    private var renderedItems: List<SharedItem> = emptyList()
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
            refreshItems()
            refreshStatus()
            syncHandler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val selectFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        Thread {
            var count = 0
            uris.forEach { uri ->
                val name = displayName(uri)
                val mime = contentResolver.getType(uri)
                val temp = File(cacheDir, "localdrop-${System.currentTimeMillis()}-${count}")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    repository.addFile(name, temp, mime)
                    temp.delete()
                    count++
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Could not share $name", Toast.LENGTH_SHORT).show() }
                }
            }
            runOnUiThread {
                Toast.makeText(this, "$count file(s) shared", Toast.LENGTH_SHORT).show()
                refreshItems()
            }
        }.start()
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val item = saveItem
        if (result.resultCode == Activity.RESULT_OK && uri != null && item?.relativePath != null) {
            val source = repository.safeFile(item.relativePath!!)
            if (source != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not save file", Toast.LENGTH_SHORT).show()
                }
            }
        }
        saveItem = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = SharedRepository(applicationContext)
        setupTheme()
        setupActions()
        binding.itemsRecycler.layoutManager = LinearLayoutManager(this)
        refreshItems()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshItems()
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
    }

    override fun onPause() {
        syncHandler.removeCallbacks(syncRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        syncHandler.removeCallbacks(syncRunnable)
        super.onDestroy()
    }

    private fun setupTheme() {
        val prefs = getSharedPreferences("localdrop_prefs", MODE_PRIVATE)
        val names = arrayOf("Light", "Dark", "System")
        binding.themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val selected = prefs.getInt("theme", 2)
        binding.themeSpinner.setSelection(selected)
        binding.themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("theme", position).apply()
                val mode = when (position) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
    }

    private fun setupActions() {
        binding.startButton.setOnClickListener { startServer() }
        binding.stopButton.setOnClickListener { stopServer() }
        binding.sendTextButton.setOnClickListener { sendText() }
        binding.textInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed
            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) {
                sendText()
                true
            } else {
                false
            }
        }
        binding.selectFilesButton.setOnClickListener {
            selectFiles.launch(arrayOf("*/*"))
        }
        binding.clearAllButton.setOnClickListener { showClearAllConfirmation() }
        binding.copyUrlButton.setOnClickListener { copyText(binding.urlText.text.toString()) }
        binding.copyCodeButton.setOnClickListener { copyText(serverCode) }
        binding.toggleCodeButton.setOnClickListener {
            codeVisible = !codeVisible
            updateCodeDisplay()
        }
    }

    private fun startServer() {
        if (ServerService.server?.isAlive == true || serverStartPending) return
        serverStartPending = true
        binding.statusText.text = "● Starting server…"
        refreshButtonStates(isRunning = false)
        try {
            startForegroundService(Intent(this, ServerService::class.java))
            awaitServerStart()
        } catch (_: RuntimeException) {
            serverStartPending = false
            refreshStatus()
            Toast.makeText(this, "Could not start the server", Toast.LENGTH_LONG).show()
        }
    }

    private fun awaitServerStart(attempt: Int = 0) {
        binding.root.postDelayed({
            val running = ServerService.server?.isAlive == true
            if (running) {
                serverStartPending = false
                refreshStatus()
            } else if (attempt < 20) {
                awaitServerStart(attempt + 1)
            } else {
                serverStartPending = false
                refreshStatus()
                Toast.makeText(this, "Server did not start. Check the Wi-Fi connection and try again.", Toast.LENGTH_LONG).show()
            }
        }, 250)
    }

    private fun stopServer() {
        if (ServerService.server?.isAlive != true) return
        ServerService.server?.stop()
        ServerService.server?.invalidateSessions()
        ServerService.server = null
        val intent = Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP)
        startService(intent)
        serverCode = ""
        codeVisible = false
        refreshStatus()
    }

    private fun refreshStatus() {
        val server = ServerService.server
        val isRunning = server?.isAlive == true
        if (isRunning && !lastServerRunning) codeVisible = false
        lastServerRunning = isRunning
        binding.statusText.text = when {
            isRunning -> "● Server running"
            serverStartPending -> "● Starting server…"
            else -> "● Server stopped"
        }
        val statusColor = when {
            isRunning -> R.color.success
            serverStartPending -> R.color.warning
            else -> R.color.text_secondary
        }
        binding.statusText.setTextColor(getColor(statusColor))
        refreshButtonStates(isRunning)
        if (isRunning) {
            val ip = ServerService.activeAddress
            val url = "http://${ip ?: "--"}:${ServerService.activePort}"
            binding.ipText.text = "IP address: ${ip ?: "unavailable"} · Port ${ServerService.activePort}"
            binding.urlText.text = url
            serverCode = server.getAccessCode()
            binding.copyUrlButton.visibility = View.VISIBLE
            binding.toggleCodeButton.visibility = View.VISIBLE
            binding.copyCodeButton.visibility = View.VISIBLE
            updateCodeDisplay()
        } else {
            binding.ipText.text = "Local address unavailable"
            binding.urlText.text = ""
            binding.codeText.text = "Access code: —"
            serverCode = ""
            binding.copyUrlButton.visibility = View.GONE
            binding.toggleCodeButton.visibility = View.GONE
            binding.copyCodeButton.visibility = View.GONE
        }
    }

    private fun refreshButtonStates(isRunning: Boolean) {
        binding.startButton.isEnabled = !isRunning && !serverStartPending
        binding.stopButton.isEnabled = isRunning
    }

    private fun updateCodeDisplay() {
        binding.codeText.text = if (serverCode.isEmpty()) "Access code: —"
            else "Access code: ${if (codeVisible) serverCode else "••••••"}"
        binding.toggleCodeButton.setImageResource(
            if (codeVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )
        binding.toggleCodeButton.contentDescription =
            if (codeVisible) "Hide access code" else "Show access code"
    }

    private fun refreshItems() {
        val items = try {
            repository.load().toList()
        } catch (_: Exception) {
            binding.itemsStatus.text = "Could not refresh shared items. The last loaded list is kept."
            binding.itemsStatus.visibility = View.VISIBLE
            return
        }
        binding.itemsStatus.visibility = View.GONE
        binding.clearAllButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.clearAllButton.isEnabled = items.isNotEmpty() && !clearAllInProgress
        if (items == renderedItems && binding.itemsRecycler.adapter != null) return
        renderedItems = items
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.itemsRecycler.adapter = ItemsAdapter(items)
    }

    private fun showClearAllConfirmation() {
        if (clearAllInProgress) return
        val count = try {
            repository.load().size
        } catch (_: Exception) {
            Toast.makeText(this, "Could not read shared items", Toast.LENGTH_LONG).show()
            return
        }
        if (count == 0) {
            refreshItems()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Clear All Shared Items?")
            .setMessage("Are you sure you want to permanently delete all shared text and files?\n\n$count item(s) will be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete All", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.danger))
            setOnClickListener {
                dialog.dismiss()
                clearAllSharedItems()
            }
        }
    }

    private fun clearAllSharedItems() {
        if (clearAllInProgress) return
        clearAllInProgress = true
        binding.clearAllButton.isEnabled = false
        binding.clearAllButton.text = "Clearing…"
        Thread {
            val result: Result<Int> = try {
                Result.success(repository.clearAll())
            } catch (error: Exception) {
                Result.failure(error)
            }
            runOnUiThread {
                clearAllInProgress = false
                binding.clearAllButton.text = "Clear All"
                refreshItems()
                binding.clearAllButton.isEnabled = renderedItems.isNotEmpty()
                val error = result.exceptionOrNull()
                if (error == null) {
                    Toast.makeText(this, "Cleared ${result.getOrThrow()} shared item(s)", Toast.LENGTH_LONG).show()
                } else {
                    val detail = error.localizedMessage?.let { ": $it" } ?: ". Please try again."
                    Toast.makeText(this, "Could not clear shared items$detail", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sendText() {
        if (sendingText) return
        val text = binding.textInput.text?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show()
            return
        }
        sendingText = true
        binding.sendTextButton.isEnabled = false
        Thread {
            try {
                repository.addText(text)
                runOnUiThread {
                    if (binding.textInput.text?.toString() == text) binding.textInput.text?.clear()
                    refreshItems()
                    Toast.makeText(this, "Text shared", Toast.LENGTH_SHORT).show()
                    sendingText = false
                    binding.sendTextButton.isEnabled = true
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not share text. Your message is still available.", Toast.LENGTH_LONG).show()
                    sendingText = false
                    binding.sendTextButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        var name = "shared-file"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        return name
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("LocalDrop", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private inner class ItemsAdapter(private val items: List<SharedItem>) : RecyclerView.Adapter<ItemsAdapter.VH>() {
        inner class VH(val binding: ItemSharedBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSharedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            b.itemName.text = if (item.type == "text") repository.preview(item.text ?: "") else item.name
            b.itemMeta.text = if (item.type == "text") "Text" else "${formatSize(item.size)} · ${item.mimeType ?: "File"}"
            b.copyButton.visibility = if (item.type == "text") View.VISIBLE else View.GONE
            b.saveButton.visibility = if (item.type == "file") View.VISIBLE else View.GONE
            b.copyButton.setOnClickListener { copyText(item.text ?: "") }
            b.saveButton.setOnClickListener {
                saveItem = item
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = item.mimeType ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, item.name)
                }
                saveFile.launch(intent)
            }
            b.itemName.setOnClickListener {
                if (item.type == "text") {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Complete text")
                        .setMessage(item.text)
                        .setPositiveButton("Copy") { _, _ -> copyText(item.text ?: "") }
                        .setNegativeButton("Close", null)
                        .show()
                }
            }
            b.deleteButton.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete ${if (item.type == "text") "text" else "file"}?")
                    .setMessage("This removes the item from LocalDrop.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        try {
                            repository.delete(item.id)
                            refreshItems()
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "Could not delete the item", Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }
        }
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1048576) return "${DecimalFormat("#.#").format(size / 1024.0)} KB"
        return "${DecimalFormat("#.#").format(size / 1048576.0)} MB"
    }

    companion object {
        private const val SYNC_INTERVAL_MS = 1_500L
    }
}
