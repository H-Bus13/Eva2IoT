package com.example.eva2iot

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.eva2iot.bluetooth.BluetoothHarp
import com.example.eva2iot.bluetooth.PairedHarp
import com.example.eva2iot.data.HarpRepository
import com.example.eva2iot.data.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HarpUiState(
    val user: String? = null, val busy: Boolean = false, val message: String = "",
    val devices: List<PairedHarp> = emptyList(), val selectedAddress: String? = null,
    val frequencies: List<String> = listOf("262", "294", "330", "349"),
    val presets: List<Preset> = emptyList(), val sending: Boolean = false,
    val history: List<String> = emptyList()
)

/** Conserva la sesión al girar el teléfono; coordina pantalla, SQLite y Bluetooth. */
class HarpViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HarpRepository(application)
    val bluetooth = BluetoothHarp(application)
    private val mutable = MutableStateFlow(HarpUiState())
    val ui = mutable.asStateFlow()
    private var failures = 0
    private var blockedUntil = 0L
    init {
        bluetooth.onTelemetry = { payload ->
            val owner = ui.value.user
            if (owner != null && !repository.record(owner, payload)) {
                bluetooth.event("No se guardó esta muestra: almacenamiento no disponible. Sigue visible en el registro.")
            }
        }
    }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }

    fun authenticate(name: String, password: String, register: Boolean) {
        if (ui.value.busy) return
        if (SystemClock.elapsedRealtime() < blockedUntil) { message("Espera 30 segundos antes de volver a intentar."); return }
        mutable.value = mutable.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val error = withContext(Dispatchers.IO) {
                    if (register) repository.register(name.trim(), password)
                    else if (repository.login(name.trim(), password)) null else "Usuario o contraseña incorrectos."
                }
                if (error == null) {
                    failures = 0
                    mutable.value = mutable.value.copy(user = name.trim(), message = "Sesión iniciada")
                    loadPresets()
                } else {
                    if (!register && ++failures >= 5) { blockedUntil = SystemClock.elapsedRealtime() + 30_000; failures = 0 }
                    message(error)
                }
            } catch (_: Exception) { message("No se pudo abrir SQLite. No se inició la sesión.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun refreshDevices() {
        try {
            val devices = bluetooth.paired()
            mutable.value = mutable.value.copy(devices = devices,
                message = if (devices.isEmpty()) "Empareja el HC-06 desde Ajustes → Bluetooth y vuelve a cargar." else "Selecciona tu HC-06.")
        } catch (error: Exception) { message(error.message ?: "No se pudo consultar Bluetooth.") }
    }
    fun select(address: String) { mutable.value = mutable.value.copy(selectedAddress = address) }
    fun connect() {
        val address = ui.value.selectedAddress ?: run { message("Selecciona un dispositivo."); return }
        bluetooth.connect(address)
    }
    fun edit(index: Int, value: String) {
        if (value.length > 4 || value.any { !it.isDigit() }) return
        mutable.value = mutable.value.copy(frequencies = ui.value.frequencies.toMutableList().apply { set(index, value) })
    }
    fun applyPreset(preset: Preset) {
        mutable.value = mutable.value.copy(frequencies = preset.frequencies.map(Int::toString), message = "${preset.name} cargada. Pulsa Enviar para aplicarla al arpa.")
    }
    private fun validFrequencies(): List<Int>? {
        val hz = ui.value.frequencies.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 100..2000 } }
        if (hz.size != 4) { message("Cada cuerda debe tener un entero entre 100 y 2000 Hz."); return null }
        return hz
    }
    fun send() {
        if (ui.value.sending || ui.value.user == null) return
        val hz = validFrequencies() ?: return
        mutable.value = mutable.value.copy(sending = true)
        viewModelScope.launch {
            try { message(if (bluetooth.sendFrequencies(hz)) "Escala aplicada: 4 confirmaciones recibidas." else "No se confirmó toda la escala. Revisa el registro y la conexión.") }
            finally { mutable.value = mutable.value.copy(sending = false) }
        }
    }
    fun savePreset(name: String) {
        val user = ui.value.user ?: return
        if (name.trim().length !in 1..40) { message("Nombre de escala: entre 1 y 40 caracteres."); return }
        val hz = validFrequencies() ?: return
        if (ui.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.save(user, name.trim(), hz) }
                loadPresets()
                message(if (saved) "Escala guardada en SQLite." else "Escala solo en memoria; se perderá al cerrar el proceso.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun loadHistory() {
        val owner = ui.value.user ?: return
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { repository.history(owner) }
            if (ui.value.user == owner) mutable.value = mutable.value.copy(history = history)
        }
    }
    private suspend fun loadPresets() {
        val user = ui.value.user ?: return
        val list = withContext(Dispatchers.IO) { repository.presets(user) }
        mutable.value = mutable.value.copy(presets = list)
        repository.storageWarning?.let(::message)
    }
    fun logout() {
        if (ui.value.sending || ui.value.busy) return
        bluetooth.disconnect(); mutable.value = HarpUiState()
    }
    override fun onCleared() { bluetooth.close(); repository.close(); super.onCleared() }
}

