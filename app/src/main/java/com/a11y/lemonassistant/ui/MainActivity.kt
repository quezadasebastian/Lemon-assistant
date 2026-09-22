package com.a11y.lemonassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private var isDictatingPin = false

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (isDictatingPin) {
                startPinVoiceDictation()
            } else {
                startVoiceRecognition()
            }
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
                        isDictatingPin = false
                        checkAndRequestAudioPermission()
                    },
                    onTriggerDictatePin = {
                        isDictatingPin = true
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

    override fun onResume() {
        super.onResume()
        // Guía por voz automática al entrar a la aplicación para usuarios ciegos
        window.decorView.postDelayed({
            announceAppStatus()
        }, 800)
    }

    private fun announceAppStatus() {
        val pin = LemonAssistantApp.instance.securePinStorage.getPin()
        val isServiceRunning = LemonA11yTransferService.isRunning

        if (!isServiceRunning) {
            tts.speakUrgent("Atención: El servicio de accesibilidad de Lemon Asistente está inactivo. Toca la pantalla para ir a Ajustes y activarlo.")
        } else if (pin.isNullOrBlank()) {
            tts.speakUrgent("Bienvenido a Lemon Asistente. Aún no has configurado tu clave de 6 dígitos. Presiona cualquier botón de volumen para dictar tu PIN.")
        } else {
            tts.speakUrgent("Lemon Asistente listo. Para transferir por voz, presiona cualquier botón físico de volumen, o toca la pantalla.")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Disparador de hardware físico para personas con discapacidad visual:
        // Presionar subir o bajar volumen en la pantalla principal activa la voz sin buscar botones en pantalla
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val pin = LemonAssistantApp.instance.securePinStorage.getPin()
            if (pin.isNullOrBlank()) {
                tts.speakUrgent("Dicta los 6 dígitos de tu clave de Lemon Cash...")
                isDictatingPin = true
                checkAndRequestAudioPermission()
            } else {
                isDictatingPin = false
                checkAndRequestAudioPermission()
            }
            return true // Consumir evento para no alterar el volumen multimedia
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (isDictatingPin) {
                startPinVoiceDictation()
            } else {
                startVoiceRecognition()
            }
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        val pin = LemonAssistantApp.instance.securePinStorage.getPin()
        if (pin.isNullOrBlank()) {
            tts.speakUrgent("Por favor, guarda tu PIN de Lemon Cash primero. Puedes dictarlo presionando la tecla de volumen.")
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

    private fun startPinVoiceDictation() {
        tts.speakUrgent("Dicta tu PIN de 6 dígitos...")
        voiceEngine = VoiceCommandEngine(
            context = this,
            onParamsExtracted = {},
            onError = { err ->
                tts.speakUrgent(err)
            }
        )
        voiceEngine?.startListeningForPin { pin ->
            LemonAssistantApp.instance.securePinStorage.savePin(pin)
            tts.speakUrgent("PIN guardado de forma segura en el almacenamiento cifrado. Ahora presiona cualquier tecla de volumen para iniciar una transferencia por voz.")
            Toast.makeText(this, "PIN guardado exitosamente", Toast.LENGTH_SHORT).show()
        }
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
    onTriggerDictatePin: () -> Unit,
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
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Lemon Asistente A11y",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = "Título principal: Lemon Asistente Accesible" }
        )

        // BOTÓN PRINCIPAL GIGANTE PARA CIEGOS: TRANSFERIR POR VOZ
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clickable { onTriggerVoiceTransfer() }
                .semantics {
                    contentDescription = "Botón principal de alta accesibilidad: Iniciar transferencia por voz. Presiona aquí o presiona cualquier botón de volumen de tu teléfono para hablar."
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "🎤", fontSize = 42.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TRANSFERIR POR VOZ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Toca aquí o pulsa cualquier botón de volumen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // Estado del Servicio de Accesibilidad
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning) MaterialTheme.colorScheme.surface else WarningAmber.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = if (isServiceRunning)
                        "Servicio de Accesibilidad activo. El asistente está listo."
                    else
                        "Servicio de Accesibilidad inactivo. Es necesario activarlo en Ajustes."
                }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isServiceRunning) "✓ Servicio Accesibilidad: ACTIVO" else "⚠ Servicio Accesibilidad: INACTIVO",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isServiceRunning) LemonGreen else WarningAmber,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isServiceRunning)
                        "Listo para navegar de forma autónoma en Lemon Cash."
                    else
                        "Toca el botón abajo para activarlo en los Ajustes del sistema.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!isServiceRunning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Activar en Ajustes de Accesibilidad", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Configuración segura de PIN (Manual o Dictado por Voz)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PIN de Acceso a Lemon Cash",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (secureStorage.hasPin()) "✓ Tu PIN está guardado y cifrado" else "⚠ No has guardado tu PIN todavía",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (secureStorage.hasPin()) LemonGreen else WarningAmber
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Botón para dictar PIN por voz (ideal para personas ciegas)
                OutlinedButton(
                    onClick = onTriggerDictatePin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Dictar clave de 6 dígitos por voz" }
                ) {
                    Text("🎙️ Dictar PIN por Voz")
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                    label = { Text("O escribe tu PIN de 6 dígitos") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Campo de texto para clave numérica de 6 dígitos" }
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

        // Prueba Manual / Rápida Determinista
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Prueba Manual por Teclado:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = recipientPhone,
                    onValueChange = { recipientPhone = it },
                    label = { Text("Teléfono de Destino (9 dígitos)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Teléfono de destino de 9 dígitos" }
                )

                OutlinedTextField(
                    value = transferAmount,
                    onValueChange = { transferAmount = it },
                    label = { Text("Monto en Soles (ej: 0.10, 5, 20)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Monto en soles a transferir" }
                )

                Button(
                    onClick = { onTriggerQuickTest(recipientPhone, transferAmount) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .semantics { contentDescription = "Ejecutar asistente automatizado con Lemon Cash" }
                ) {
                    Text("Ejecutar Asistente con Lemon Cash", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Panel de instrucciones de hardware
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Control por Teclas Físicas de Volumen",
                    style = MaterialTheme.typography.titleMedium,
                    color = LemonGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• En la app: Presiona Subir o Bajar Volumen para hablar inmediatamente.\n" +
                            "• En confirmación final:\n" +
                            "  - Subir Volumen (2 veces): Autoriza y emite el pago.\n" +
                            "  - Bajar Volumen (1 vez): Cancela sin debitar dinero.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
