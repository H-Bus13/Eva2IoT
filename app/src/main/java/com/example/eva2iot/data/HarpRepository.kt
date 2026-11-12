package com.example.eva2iot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Preset(val name: String, val frequencies: List<Int>)

/** SQLite guarda usuarios y escalas; nunca guardamos la contraseña original. */
class HarpRepository(context: Context) : SQLiteOpenHelper(context, "arpa.db", null, 2) {
    private val cache = linkedMapOf<String, List<Int>>()
    var storageWarning: String? = null
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users (name TEXT PRIMARY KEY, salt TEXT NOT NULL, hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE presets (owner TEXT NOT NULL, name TEXT NOT NULL, hz TEXT NOT NULL, PRIMARY KEY(owner,name))")
        createTelemetry(db)
    }

    private fun createTelemetry(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS telemetry (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, time INTEGER NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTelemetry(db)
    }

    fun record(owner: String, payload: String): Boolean = try {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("owner", owner); put("time", System.currentTimeMillis()); put("payload", payload) }
            check(db.insert("telemetry", null, values) != -1L)
            // Retiene las últimas 200 muestras por usuario para limitar el espacio utilizado.
            db.execSQL("DELETE FROM telemetry WHERE owner=? AND id NOT IN (SELECT id FROM telemetry WHERE owner=? ORDER BY id DESC LIMIT 200)", arrayOf(owner, owner))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        true
    } catch (_: Exception) { false }

    fun history(owner: String): List<String> = try {
        val rows = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT time,payload FROM telemetry WHERE owner=? ORDER BY id DESC LIMIT 12", arrayOf(owner)).use { cursor ->
            val format = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault())
            while (cursor.moveToNext()) rows += "${format.format(java.util.Date(cursor.getLong(0)))} · ${cursor.getString(1)}"
        }
        rows
    } catch (_: Exception) { listOf("No se pudo consultar el historial local.") }

    // PBKDF2 dificulta adivinar contraseñas a partir de una copia de la base de datos.
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    fun register(name: String, password: String): String? {
        if (!name.matches(Regex("[A-Za-z0-9_.-]{3,30}"))) return "Usuario: 3 a 30 letras, números, punto, guion o guion bajo."
        if (password.length !in 8..128) return "La contraseña debe tener entre 8 y 128 caracteres."
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return try {
            val values = ContentValues().apply {
                put("name", name); put("salt", encode(salt)); put("hash", encode(derive(password, salt)))
            }
            if (writableDatabase.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                "Ese usuario ya existe." else null
        } catch (_: Exception) { "No se pudo guardar el usuario. Revisa el almacenamiento." }
    }

    fun login(name: String, password: String): Boolean {
        if (password.length > 128) return false
        // Los argumentos separados evitan introducir SQL desde los campos del login.
        readableDatabase.rawQuery("SELECT salt,hash FROM users WHERE name=?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst()) return false
            return MessageDigest.isEqual(decode(cursor.getString(1)), derive(password, decode(cursor.getString(0))))
        }
    }

    fun presets(owner: String): List<Preset> {
        try {
            readableDatabase.rawQuery("SELECT name,hz FROM presets WHERE owner=? ORDER BY name", arrayOf(owner)).use { cursor ->
                while (cursor.moveToNext()) {
                    val hz = cursor.getString(1).split(',').mapNotNull { it.toIntOrNull() }
                    if (hz.size == 4 && hz.all { it in 100..2000 }) cache["$owner/${cursor.getString(0)}"] = hz
                }
            }
            storageWarning = null
        } catch (_: Exception) { storageWarning = "No se pudo leer SQLite; se muestran los datos en memoria." }
        return cache.filterKeys { it.startsWith("$owner/") }.map { Preset(it.key.substringAfter('/'), it.value) }
    }

    fun save(owner: String, name: String, hz: List<Int>): Boolean {
        require(name.isNotBlank() && name.length <= 40 && hz.size == 4 && hz.all { it in 100..2000 })
        cache["$owner/$name"] = hz.toList() // Mantiene la escala disponible aunque falle el disco.
        return try {
            val values = ContentValues().apply { put("owner", owner); put("name", name); put("hz", hz.joinToString(",")) }
            check(writableDatabase.insertWithOnConflict("presets", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            storageWarning = null
            true
        } catch (_: Exception) {
            storageWarning = "Guardado solo en memoria: se perderá al cerrar el proceso."
            false
        }
    }
}
