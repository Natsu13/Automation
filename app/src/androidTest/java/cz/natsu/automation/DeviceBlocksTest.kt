package cz.natsu.automation

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.SecretStore
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.ValueStore
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.JsonTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Blocks that need a real Android: images, stored values, secrets. */
@RunWith(AndroidJUnit4::class)
class DeviceBlocksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun block(type: String, vararg params: Pair<String, ParamValue>): Block {
        val def = Blocks.get(type) ?: error("unknown block $type")
        return def.newBlock().let { it.copy(params = it.params + params) }
    }

    private fun rich(vararg parts: Any) = ParamValue.Rich(parts.map {
        when (it) {
            is String -> Segment.Text(it)
            is VarRef -> Segment.Var(it)
            else -> error("bad part")
        }
    })

    private fun run(vararg blocks: Block) = runAs(null, *blocks)

    /** Runs [blocks] as if they belonged to automation [automationId]. */
    private fun runAs(automationId: Long?, vararg blocks: Block) =
        runBlocking { AutomationRunner.run(context, blocks.toList(), "", automationId = automationId) }

    /** Value of "Načíst hodnotu" for [name] when run as [automationId]. */
    private fun load(automationId: Long?, name: String, shared: Boolean = false): String? {
        val get = block(
            "store_get", "key" to richText(name), "fallback" to richText("nic"),
            "scope" to ParamValue.Choice(if (shared) "shared" else "own"),
        )
        return runAs(automationId, get, block("return", "text" to rich(VarRef.BlockOutput(get.id)))).output
    }

    private fun save(automationId: Long?, name: String, value: String, shared: Boolean = false) {
        runAs(
            automationId,
            block(
                "store_set", "key" to richText(name), "value" to richText(value),
                "scope" to ParamValue.Choice(if (shared) "shared" else "own"),
            )
        )
    }

    @Test
    fun freeSpaceIconExampleDrawsATintedIconWithText() {
        val example = cz.natsu.automation.ui.list.Examples.all.first { it.name == "Ikona volného místa" }
        val blocks = example.build().blocks
        val icon = (blocks.first { it.type == "image_edit" }.params["source"] as ParamValue.Rich).segments
            .filterIsInstance<Segment.Text>().single().text
        assertTrue("icon is stored in the automation", icon.startsWith("data:image/png;base64,") && icon.length > 200)

        // Everything but the notification, then return the picture.
        val labelled = blocks.first { it.type == "image_text" }
        val result = run(*blocks.dropLast(1).toTypedArray(), block("return", "text" to rich(VarRef.BlockOutput(labelled.id))))
        assertNull(result.error)
        val file = File(Uri.parse(result.output).path!!)
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.path)
        assertEquals(192, bitmap.width)
        assertEquals(192, bitmap.height)
        // Some pixel has one of the three state colours.
        val states = setOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFF43A047.toInt())
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertTrue(pixels.any { it in states })
        context.getExternalFilesDir(null)?.let { file.copyTo(File(it, "free_space_icon.png"), overwrite = true) }

        // The whole example, with the notification (skipped when notifications are off for the app).
        val full = run(*blocks.toTypedArray())
        assertTrue(full.error == null || full.requirement != null)
    }

    @Test
    fun notificationHasTapActionAndItsOwnStatusBarIcon() {
        fun icon(vector: androidx.compose.ui.graphics.vector.ImageVector, color: Int) =
            cz.natsu.automation.engine.EmbeddedImage.encode(cz.natsu.automation.engine.EmbeddedImage.fromIcon(vector, color))
        val result = run(
            block(
                "notification",
                "title" to richText("Test obrázku"),
                "text" to richText("klepni"),
                "quiet" to ParamValue.Bool(true),
                "name" to richText("test_tap"),
                "image" to richText(icon(Icons.Rounded.Storage, Color.RED)),
                "small_icon" to ParamValue.Choice("other"),
                "small_image" to richText(icon(Icons.Rounded.Star, Color.WHITE)),
                "tap_action" to ParamValue.Choice("url"),
                "tap_url" to richText("example.com"),
                "b1_action" to ParamValue.Choice("none"),
            )
        )
        assertNull(result.error)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val shown = manager.activeNotifications.first { it.notification.extras.getString("android.title") == "Test obrázku" }
        assertTrue("tap does something", shown.notification.contentIntent != null)
        assertEquals(android.graphics.drawable.Icon.TYPE_BITMAP, shown.notification.smallIcon.type)
        assertTrue("large image", shown.notification.getLargeIcon() != null)
    }

    @Test
    fun storedValuesBelongToTheirAutomation() {
        ValueStore.removeAutomation(context, 901)
        ValueStore.removeAutomation(context, 902)
        ValueStore.remove(context, null, "last_state")

        save(901, "last_state", "A")
        save(902, "last_state", "B")
        assertEquals("A", load(901, "last_state"))
        assertEquals("B", load(902, "last_state"))
        // Not visible as a shared value.
        assertEquals("nic", load(901, "last_state", shared = true))

        save(901, "last_state", "S", shared = true)
        assertEquals("S", load(902, "last_state", shared = true))
        assertEquals("A", load(901, "last_state"))

        // Listing mixes shared and own values (the settings screens show it).
        val listed = ValueStore.all(context).filter { it.name == "last_state" }
        assertEquals(listOf(null, 901L, 902L), listed.map { it.automationId })

        // Deleting an automation drops only its own values.
        ValueStore.removeAutomation(context, 901)
        assertEquals("nic", load(901, "last_state"))
        assertEquals("B", load(902, "last_state"))
        assertEquals("S", load(902, "last_state", shared = true))

        ValueStore.removeAutomation(context, 902)
        ValueStore.remove(context, null, "last_state")
    }

    /** A 400 × 200 PNG with a red left half, as a file:// address. */
    private fun testImage(): String {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 400) for (y in 0 until 200) bitmap.setPixel(x, y, if (x < 200) Color.RED else Color.BLUE)
        val file = File(context.cacheDir, "test-image.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }

    /** Runs one image_edit and returns image_info of the result as JSON. */
    private fun edit(vararg params: Pair<String, ParamValue>): Map<String, String> {
        val edited = block("image_edit", "source" to richText(testImage()), *params)
        val info = block("image_info", "source" to rich(VarRef.BlockOutput(edited.id)))
        val result = run(edited, info, block("return", "text" to rich(VarRef.BlockOutput(info.id))))
        assertNull(result.error)
        val json = JsonTools.parse(result.output!!)
        return listOf("width", "height", "type").associateWith {
            JsonTools.asText(JsonTools.select(json, JsonTools.parsePath(it)))
        }
    }

    @Test
    fun resizeKeepsRatioAndFormat() {
        val info = edit("op" to ParamValue.Choice("resize"), "max_size" to ParamValue.Number(100f))
        assertEquals(mapOf("width" to "100", "height" to "50", "type" to "image/png"), info)
    }

    @Test
    fun resizeNeverEnlarges() {
        val info = edit("op" to ParamValue.Choice("resize"), "max_size" to ParamValue.Number(4000f))
        assertEquals("400", info["width"])
    }

    @Test
    fun rotateSwapsSides() {
        val info = edit("op" to ParamValue.Choice("rotate"), "angle" to ParamValue.Choice("90"))
        assertEquals("200", info["width"])
        assertEquals("400", info["height"])
    }

    @Test
    fun squareCropsToShorterSide() {
        val info = edit("op" to ParamValue.Choice("square"))
        assertEquals("200", info["width"])
        assertEquals("200", info["height"])
    }

    @Test
    fun convertChangesType() {
        assertEquals("image/jpeg", edit("op" to ParamValue.Choice("convert"), "format" to ParamValue.Choice("jpeg"))["type"])
        assertEquals("image/webp", edit("op" to ParamValue.Choice("convert"), "format" to ParamValue.Choice("webp"))["type"])
    }

    @Test
    fun flipMirrorsPixels() {
        val edited = block(
            "image_edit", "source" to richText(testImage()),
            "op" to ParamValue.Choice("flip"), "flip" to ParamValue.Choice("horizontal"),
        )
        val result = run(edited, block("return", "text" to rich(VarRef.BlockOutput(edited.id))))
        assertNull(result.error)
        val bitmap = android.graphics.BitmapFactory.decodeFile(Uri.parse(result.output).path)
        assertEquals(Color.BLUE, bitmap.getPixel(10, 100))
        assertEquals(Color.RED, bitmap.getPixel(390, 100))
    }

    @Test
    fun storedValueSurvivesRunsAndCanBeRemoved() {
        ValueStore.removeAutomation(context, 900)
        save(900, "test_key", "42")
        assertEquals("42", load(900, "test_key"))
        runAs(900, block("store_set", "mode" to ParamValue.Choice("remove"), "key" to richText("test_key")))
        assertEquals("nic", load(900, "test_key"))
    }

    @Test
    fun secretIsUsedButMaskedInLog() {
        SecretStore.set(context, "test_token", "tajne-heslo-123")
        assertEquals("tajne-heslo-123", SecretStore.get(context, "test_token"))
        val text = block("text", "text" to rich("Bearer ", VarRef.Secret("test_token")))
        val result = run(text, block("return", "text" to rich(VarRef.BlockOutput(text.id))))
        assertEquals("Bearer tajne-heslo-123", result.output)
        val logged = result.log.first { it.blockId == text.id }.output!!
        assertTrue(logged, "tajne-heslo-123" !in logged)
        SecretStore.remove(context, "test_token")
        val missing = run(block("text", "text" to rich(VarRef.Secret("test_token"))))
        // The message is in the language of the app (English on an English phone).
        val expected = cz.natsu.automation.i18n.tr("tajná hodnota \"%s\" neexistuje (Nastavení → Tajné hodnoty)", "test_token")
        assertTrue(missing.error, missing.error!!.contains(expected))
    }
}
