package com.interviewmitra

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.interviewmitra.domain.ResponseMode
import com.interviewmitra.domain.SpeakerRole
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<InterviewViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme { InterviewMitraApp(viewModel, ::hasMicPermission, ::requestMicPermission, ::readResume) }
        }
    }
    private fun hasMicPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestMicPermission() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
    private fun readResume(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    if (contentResolver.getType(uri) == "application/pdf" || uri.toString().endsWith(".pdf", true)) {
                        PDDocument.load(input).use { PDFTextStripper().getText(it) }
                    } else input.bufferedReader().readText()
                }.orEmpty()
            }.getOrElse { "" }
            withContext(Dispatchers.Main) { if (text.isNotBlank()) viewModel.setResumeText(text) }
        }
    }
    override fun onDestroy() { viewModel.stop(); super.onDestroy() }
}

@Composable
private fun InterviewMitraApp(vm: InterviewViewModel, hasMic: () -> Boolean, requestMic: () -> Unit, readResume: (Uri) -> Unit) {
    val state by vm.state.collectAsState()
    var microphoneDenied by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(readResume) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.stage) {
            SessionStage.SETUP -> SetupScreen(state, { picker.launch(arrayOf("application/pdf", "text/plain")) }, vm::setMode, microphoneDenied) {
                if (hasMic()) vm.openCalibration() else { microphoneDenied = true; requestMic() }
            }
            SessionStage.CALIBRATION -> CalibrationScreen(state, vm::beginCalibration, vm::beginInterview)
            SessionStage.LIVE -> LiveScreen(state, vm::setMode, vm::stop)
            SessionStage.ENDED -> EndScreen(vm::openCalibration)
        }
    }
}

@Composable private fun SetupScreen(state: InterviewUiState, upload: () -> Unit, setMode: (ResponseMode) -> Unit, microphoneDenied: Boolean, continueToCalibration: () -> Unit) =
    Page("Interview Mitra", "Real-time practice answers, grounded in your background.") {
        Card { Column(Modifier.padding(18.dp)) {
            Text("Candidate background", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(if (state.resumeText == null) "Upload a PDF or text resume (optional)." else "Resume ready — ${state.resumeText!!.length} characters extracted.")
            Spacer(Modifier.height(12.dp)); OutlinedButton(upload) { Text(if (state.resumeText == null) "Upload resume" else "Replace resume") }
        } }
        Spacer(Modifier.height(16.dp)); ResponseModePicker(state.responseMode, setMode)
        if (microphoneDenied) Text("Microphone access is required to start an interview. Allow it in the system prompt or Android settings.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.weight(1f)); Button(continueToCalibration, Modifier.fillMaxWidth()) { Text("Continue to voice calibration") }
    }

@Composable private fun CalibrationScreen(state: InterviewUiState, calibrate: () -> Unit, start: () -> Unit) =
    Page("Voice calibration", "This assigns your voice to the candidate role. Your speech will never be sent to the answer model.") {
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.candidateSpeakerId == null) Button(calibrate, Modifier.height(68.dp)) { Text("Hold and say a short test phrase") }
            else Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Voice confirmed", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp)); Button(start) { Text("Start interview") } }
        }
        Spacer(Modifier.height(16.dp)); Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable private fun LiveScreen(state: InterviewUiState, setMode: (ResponseMode) -> Unit, stop: () -> Unit) =
    Page("Live interview", state.status) {
        ResponseModePicker(state.responseMode, setMode)
        Spacer(Modifier.height(12.dp))
        state.currentQuestion?.let { LabeledCard("Current interviewer question", it, Color(0xFF1565C0)) }
        if (state.currentQuestion == null) LabeledCard("Waiting", "No interviewer question detected yet.", Color(0xFF546E7A))
        Spacer(Modifier.height(12.dp))
        LabeledCard("Suggested answer", state.answer.ifBlank { if (state.answerError != null) state.answerError!! else "Answers will appear here as they stream." }, Color(0xFF2E7D32))
        Spacer(Modifier.height(12.dp)); Text("Live transcript", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) { items(state.transcript) { line ->
            val label = if (line.role == SpeakerRole.CANDIDATE) "YOU" else "INTERVIEWER"
            Text("$label  ${line.text}", color = if (line.role == SpeakerRole.CANDIDATE) Color(0xFF6A1B9A) else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 5.dp))
        } }
        Button(stop, Modifier.fillMaxWidth()) { Text("Stop session") }
    }

@Composable private fun EndScreen(restart: () -> Unit) = Page("Session ended", "Your interview data was kept only in memory and has been cleared.") { Spacer(Modifier.weight(1f)); Button(restart, Modifier.fillMaxWidth()) { Text("Start another session") } }

@Composable private fun Page(title: String, subtitle: String, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)); body()
    }
}
@Composable private fun ResponseModePicker(selected: ResponseMode, setMode: (ResponseMode) -> Unit) {
    Column { Text("Answer style", fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { ResponseMode.entries.forEach { mode -> if (mode == selected) Button({ setMode(mode) }) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) } else OutlinedButton({ setMode(mode) }) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) } } } }
}
@Composable private fun LabeledCard(label: String, text: String, color: Color) { Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .1f)), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(label, color = color, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text) } } }
