package com.penumbraos.hook

import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * Reads ironman's NotificationDatabase directly (we run in-process, so we have
 * the file + permissions). Ported from the proven Frida voice_handlers SQL.
 *
 *  - catchUpSummary(): Siri-style "who + counts only, no content" readout.
 *  - readFrom(name) / readNewest(): on-demand content readout (marks read).
 *
 * Raw SQLite (not the Room DAO) keeps this independent of the app's DB singletons.
 */
object NotificationReader {
    private const val TAG = "PenumbraHook"
    private const val DB_PATH =
        "/data/user/0/hu.ma.ne.ironman/databases/NotificationDatabase-db"
    private const val MSGS = "humane.experience.messages"
    private const val DIALER = "humane.experience.dialer"

    // Persisted "high-water mark" for catch-me-up: the timestamp of the newest item
    // we've already summarized. Catch-up only reports items NEWER than this, so it
    // never re-announces the same old stuff. Lives in ironman's own files dir.
    private const val CATCHUP_MARK_PATH =
        "/data/user/0/hu.ma.ne.ironman/files/aipin_catchup_mark"

    private fun readCatchUpMark(): Long = try {
        val f = java.io.File(CATCHUP_MARK_PATH)
        if (f.exists()) f.readText().trim().toLongOrNull() ?: 0L else 0L
    } catch (t: Throwable) { 0L }

    private fun writeCatchUpMark(ts: Long) {
        try {
            val f = java.io.File(CATCHUP_MARK_PATH)
            f.parentFile?.mkdirs()
            f.writeText(ts.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "writeCatchUpMark failed: ${t.message}")
        }
    }

    private fun open(readOnly: Boolean): SQLiteDatabase? = try {
        SQLiteDatabase.openDatabase(
            DB_PATH, null,
            if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
        )
    } catch (t: Throwable) {
        Log.e(TAG, "NotificationReader open failed: ${t.message}")
        null
    }

    /**
     * Counts + senders only — NO message content. e.g.
     * "New message from Mom. 2 new messages from Alex. Missed call from Dad."
     * Returns null when there's nothing NEW since the last catch-up. Does NOT mark read.
     */
    fun catchUpSummary(): String? {
        val db = open(true) ?: return null
        try {
            val mark = readCatchUpMark()
            val msgBy = LinkedHashMap<String, Int>()
            val callBy = LinkedHashMap<String, Int>()
            var newestTs = mark
            // Only items NEWER than the last catch-up high-water mark, so we never
            // repeat what we already summarized (even if still unread).
            db.rawQuery(
                "SELECT experienceIdentifier, sender, timestamp FROM notification " +
                    "WHERE isRead = 0 AND isSentBySelf = 0 AND timestamp > ? ORDER BY timestamp ASC",
                arrayOf(mark.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val kind = c.getString(0) ?: ""
                    val sender = (c.getString(1) ?: "someone").trim().ifEmpty { "someone" }
                    val ts = c.getLong(2)
                    if (ts > newestTs) newestTs = ts
                    when {
                        kind.contains("messages") -> msgBy[sender] = (msgBy[sender] ?: 0) + 1
                        kind.contains("dialer") -> callBy[sender] = (callBy[sender] ?: 0) + 1
                    }
                }
            }
            val parts = ArrayList<String>()
            for ((name, n) in msgBy) {
                parts.add(if (n == 1) "New message from $name." else "$n new messages from $name.")
            }
            for ((name, n) in callBy) {
                parts.add(if (n == 1) "Missed call from $name." else "$n missed calls from $name.")
            }
            if (parts.isEmpty()) return null
            // Advance the mark so a repeat "catch me up" won't re-announce these.
            writeCatchUpMark(newestTs)
            return parts.joinToString(" ")
        } catch (t: Throwable) {
            Log.e(TAG, "catchUpSummary failed: ${t.message}")
            return null
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * Read a specific contact's messages in order. Prefers unread; if none, falls
     * back to the most recent 3. Marks the unread ones read. Returns null if none.
     */
    fun readFrom(name: String): String? {
        val db = open(false) ?: return null
        try {
            val items = ArrayList<String>()
            var display: String? = null
            db.rawQuery(
                "SELECT sender, content FROM notification " +
                    "WHERE isSentBySelf = 0 AND isRead = 0 AND experienceIdentifier LIKE '%messages%' " +
                    "AND sender LIKE ? ORDER BY timestamp ASC", arrayOf("%$name%")
            ).use { c ->
                while (c.moveToNext()) {
                    if (display == null) display = c.getString(0)
                    c.getString(1)?.let { items.add(it) }
                }
            }
            if (items.isNotEmpty()) {
                db.execSQL(
                    "UPDATE notification SET isRead = 1 WHERE isSentBySelf = 0 AND isRead = 0 " +
                        "AND experienceIdentifier LIKE '%messages%' AND sender LIKE ?", arrayOf("%$name%")
                )
                return narrateMessages(display ?: name, items, unread = true)
            }
            // Fallback: most recent 3 (already read) — don't change read state.
            db.rawQuery(
                "SELECT sender, content FROM notification " +
                    "WHERE isSentBySelf = 0 AND experienceIdentifier LIKE '%messages%' " +
                    "AND sender LIKE ? ORDER BY timestamp DESC LIMIT 3", arrayOf("%$name%")
            ).use { c ->
                while (c.moveToNext()) {
                    if (display == null) display = c.getString(0)
                    c.getString(1)?.let { items.add(0, it) } // chronological
                }
            }
            return if (items.isEmpty()) null else narrateMessages(display ?: name, items, unread = false)
        } catch (t: Throwable) {
            Log.e(TAG, "readFrom failed: ${t.message}")
            return null
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * Read the newest unread sender's messages, chronological. Marks them read.
     * Returns null if there are no unread messages.
     */
    fun readNewest(): String? {
        val db = open(false) ?: return null
        try {
            var sender: String? = null
            db.rawQuery(
                "SELECT sender FROM notification WHERE isSentBySelf = 0 AND isRead = 0 " +
                    "AND experienceIdentifier LIKE '%messages%' ORDER BY timestamp DESC LIMIT 1", null
            ).use { c -> if (c.moveToNext()) sender = c.getString(0) }
            val s = sender ?: return null
            val items = ArrayList<String>()
            db.rawQuery(
                "SELECT content FROM notification WHERE isSentBySelf = 0 AND isRead = 0 " +
                    "AND experienceIdentifier LIKE '%messages%' AND sender = ? ORDER BY timestamp ASC",
                arrayOf(s)
            ).use { c -> while (c.moveToNext()) c.getString(0)?.let { items.add(it) } }
            if (items.isEmpty()) return null
            db.execSQL(
                "UPDATE notification SET isRead = 1 WHERE isSentBySelf = 0 AND isRead = 0 " +
                    "AND experienceIdentifier LIKE '%messages%' AND sender = ?", arrayOf(s)
            )
            return narrateMessages(s, items, unread = true)
        } catch (t: Throwable) {
            Log.e(TAG, "readNewest failed: ${t.message}")
            return null
        } finally {
            runCatching { db.close() }
        }
    }

    private fun narrateMessages(name: String, items: List<String>, unread: Boolean): String {
        val header = when {
            items.size == 1 && unread -> "New message from $name."
            items.size == 1 -> "Most recent from $name."
            unread -> "${items.size} new messages from $name."
            else -> "Last ${items.size} from $name."
        }
        // ". " separators give TTS a natural pause between messages.
        return (listOf(header) + items).joinToString(". ")
    }
}
