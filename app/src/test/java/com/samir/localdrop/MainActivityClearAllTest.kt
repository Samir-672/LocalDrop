package com.samir.localdrop

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.samir.localdrop.data.SharedRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MainActivityClearAllTest {
    private lateinit var repository: SharedRepository

    @Before
    fun setUp() {
        repository = SharedRepository(RuntimeEnvironment.getApplication())
        repository.clearAll()
    }

    @After
    fun tearDown() {
        repository.clearAll()
    }

    @Test
    fun clear_all_button_is_hidden_when_empty_and_cancel_preserves_items() {
        val emptyActivity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(View.GONE, emptyActivity.get().findViewById<Button>(R.id.clearAllButton).visibility)
        emptyActivity.pause().stop().destroy()

        repository.addText("Keep this item")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val button = controller.get().findViewById<Button>(R.id.clearAllButton)
        assertEquals(View.VISIBLE, button.visibility)
        button.performClick()

        val dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
            ?: throw AssertionError("Clear All confirmation dialog was not shown")
        val message = dialog.window!!.decorView.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains("1 item"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()

        assertEquals(1, repository.load().size)
        controller.pause().stop().destroy()
    }

    @Test
    fun confirming_clear_all_removes_local_files_and_metadata() {
        val context = RuntimeEnvironment.getApplication()
        repository.addText("Delete this text")
        val source = File(context.cacheDir, "main-clear-all-source.txt").apply {
            writeText("Delete this file")
        }
        val item = repository.addFile("delete-this.txt", source, "text/plain")
        source.delete()
        val storedFile = repository.safeFile(item.relativePath!!)!!

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clearButton = controller.get().findViewById<Button>(R.id.clearAllButton)
        clearButton.performClick()
        val dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
            ?: throw AssertionError("Clear All confirmation dialog was not shown")
        val message = dialog.window!!.decorView.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains("2 item"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Clearing…", clearButton.text.toString())

        val deadline = System.currentTimeMillis() + 5_000
        while (repository.load().isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(repository.load().isEmpty())
        assertFalse(storedFile.exists())
        assertTrue(repository.sharedDir.listFiles()!!.isEmpty())
        controller.pause().stop().destroy()
    }
}
