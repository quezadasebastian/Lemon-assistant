package com.a11y.lemonassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.a11y.lemonassistant.LemonAssistantApp
import com.a11y.lemonassistant.audio.InclusiveTtsManager
import com.a11y.lemonassistant.audio.VoiceCommandEngine
import com.a11y.lemonassistant.fsm.TransferParams
import com.a11y.lemonassistant.service.LemonA11yTransferService
import com.a11y.lemonassistant.ui.theme.LemonAssistantTheme
import com.a11y.lemonassistant.ui.theme.LemonGreen
import com.a11y.lemonassistant.ui.theme.WarningAmber

class MainActivity : ComponentActivity() {

    private lateinit var tts: InclusiveTtsManager
    private var voiceEngine: VoiceCommandEngine? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceRecognition()
        } else {
            tts.speakUrgent("Se requiere permiso de micrófono para usar comandos de voz.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = InclusiveTtsManager(this)

        setContent {
            LemonAssistantTheme {
                MainScreen(
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onTriggerVoiceTransfer = {
                        checkAndRequestAudioPermission()
                    },
                    onTriggerQuickTest = { phone, amount ->
                        executeTransfer(phone, amount)
                    },
                    onSpeak = { text ->
                        tts.speak(text)
                    }
                )
            }
        }
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        val pin = LemonAssistantApp.instance.securePinStorage.getPin()
        if (pin.isNullOrBlank()) {
            tts.speakUrgent("Por favor, ingresa y guarda tu PIN de Lemon Cash primero en la pantalla principal.")
            Toast.makeText(this, "Guarda tu PIN primero", Toast.LENGTH_SHORT).show()
            return
        }

        tts.speakUrgent("Escuchando. Indica el monto y número de destino.")
        voiceEngine = VoiceCommandEngine(
            context = this,
            onParamsExtracted = { params ->
                executeTransferWithParams(params)
            },
            onError = { err ->
                tts.speakUrgent(err)
            }
        )
        voiceEngine?.startListening(pin)
    }

    private fun executeTransfer(phone: String, amount: String) {
        val pin = LemonAssistantApp.instance.securePinStorage.getPin()
        if (pin.isNullOrBlank()) {
            tts.speakUrgent("Primero guarda tu PIN de 6 dígitos.")
            return
        }
        val cleanPhone = phone.trim()
        val cleanAmount = amount.trim()
        if (cleanPhone.isBlank()) {
            tts.speakUrgent("Por favor, ingresa el número de teléfono de destino.")
            Toast.makeText(this, "Ingresa un número de teléfono", Toast.LENGTH_SHORT).show()
            return
        }
        if (cleanPhone.length != 9 || !cleanPhone.all { it.isDigit() }) {
            tts.speakUrgent("El número de teléfono debe tener exactamente 9 dígitos.")
            Toast.makeText(this, "Número de destino inválido", Toast.LENGTH_SHORT).show()
            return
        }
        if (cleanAmount.isBlank()) {
            tts.speakUrgent("Por favor, ingresa el monto a transferir.")
            Toast.makeText(this, "Ingresa un monto", Toast.LENGTH_SHORT).show()
            return
        }
        val parsedAmount = cleanAmount.replace(',', '.').toDoubleOrNull()
        if (parsedAmount == null || parsedAmount <= 0.0) {
            tts.speakUrgent("El monto ingresado no es válido.")
            Toast.makeText(this, "Monto inválido", Toast.LENGTH_SHORT).show()
            return
        }
        val formattedAmount = if (cleanAmount.contains(".")) cleanAmount else "$cleanAmount.00"
        val params = TransferParams(
            loginPin = pin,
            recipientPhone = cleanPhone,
            amount = formattedAmount,
            currency = "Soles",
            destinationNetwork = "YAPE",
            requireManualConfirm = true
        )
        executeTransferWithParams(params)
    }

    private fun executeTransferWithParams(params: TransferParams) {
        val service = LemonA11yTransferService.instance
        if (service != null) {
            service.startTransfer(params)
        } else {
            tts.speakUrgent("El servicio de accesibilidad no está activo. Actívalo en Ajustes para continuar.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        voiceEngine?.destroy()
    }
}

@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onTriggerVoiceTransfer: () -> Unit,
    onTriggerQuickTest: (phone: String, amount: String) -> Unit,
    onSpeak: (String) -> Unit
) {
    val context = LocalContext.current
    val secureStorage = remember { LemonAssistantApp.instance.securePinStorage }
    var pinInput by remember { mutableStateOf(secureStorage.getPin() ?: "") }
    var recipientPhone by remember { mutableStateOf("") }
    var transferAmount by remember { mutableStateOf("") }
    val isServiceRunning = LemonA11yTransferService.isRunning

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = "Lemon Asistente A11y",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = "Título: Lemon Asistente Accesible" }
        )

        // Estado del Servicio de Accesibilidad
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning) MaterialTheme.colorScheme.surface else WarningAmber.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isServiceRunning) "✓ Servicio de Accesibilidad: ACTIVO" else "⚠ Servicio de Accesibilidad: INACTIVO",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isServiceRunning) LemonGreen else WarningAmber
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isServiceRunning)
                        "El asistente está listo para interactuar con Lemon Cash."
                    else
                        "Es indispensable activar el servicio en los Ajustes del sistema para permitir la asistencia automatizada.",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!isServiceRunning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Activar en Ajustes de Accesibilidad", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Configuración segura de PIN
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PIN de Acceso a Lemon Cash",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                    label = { Text("PIN de 6 dígitos") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (pinInput.length == 6) {
                            secureStorage.savePin(pinInput)
                            onSpeak("PIN guardado de forma segura en el almacenamiento cifrado.")
                            Toast.makeText(context, "PIN guardado de forma segura", Toast.LENGTH_SHORT).show()
                        } else {
                            onSpeak("El PIN debe tener exactamente 6 dígitos numéricos.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar PIN Cifrado", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Disparador de Transferencia por Voz o Prueba
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Acciones de Transferencia",
                    style = MaterialTheme.typography.titleLarge
                )

                Button(
                    onClick = onTriggerVoiceTransfer,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text("🎤 Iniciar Transferencia por Voz", style = MaterialTheme.typography.labelLarge)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Prueba Rápida Determinista:",
                    style = MaterialTheme.typography.bodyLarge
                )

                OutlinedTextField(
                    value = recipientPhone,
                    onValueChange = { recipientPhone = it },
                    label = { Text("Teléfono de Destino (9 dígitos)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = transferAmount,
                    onValueChange = { transferAmount = it },
                    label = { Text("Monto (Soles)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { onTriggerQuickTest(recipientPhone, transferAmount) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Ejecutar Asistente con Lemon Cash", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Panel de instrucciones de seguridad física
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Seguridad Física (Human-in-the-Loop)",
                    style = MaterialTheme.typography.titleLarge,
                    color = LemonGreen
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Durante la pantalla de confirmación final:\n" +
                            "• Subir Volumen (2 veces): Autoriza y confirma el pago.\n" +
                            "• Bajar Volumen (1 vez): Cancela inmediatamente la operación.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
