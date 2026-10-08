package nl.janick.cyclus

import android.content.Context
import java.io.File

/** Bewaart alle cyclusgegevens als één bestand in de app-map (gaat mee in de Android-back-up). */
object Storage {
    private const val FILE = "cyclus-data.json"

    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readText() else null
    }

    fun write(ctx: Context, json: String) {
        // Eerst naar een tijdelijk bestand, dan vervangen: zo raakt het bestand nooit half beschreven.
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(json)
        val f = File(ctx.filesDir, FILE)
        if (!tmp.renameTo(f)) {
            f.writeText(json)
            tmp.delete()
        }
    }
}
