package app.resound.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill

/** 自定义识别沿用手机分人；对齐地址由用户填写，不包含设备默认地址。 */
@Composable
internal fun CustomAsrOptions(settings: AndroidTranscribeSettings) {
    val saved by settings.alignmentEndpoint.collectAsStateWithLifecycle()
    val actual by settings.localStatus.collectAsStateWithLifecycle()
    var input by remember(saved) { mutableStateOf(saved) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val c = Rs.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(12.dp))
        app.resound.ui.voiceprint.SpeakerSegmentationSettings()
        Text("时间戳对齐地址（可选）", style = RsType.body, color = c.text)
        SettingsInput(input, { input = it.trim(); message = "" }, "完整的 /v1/audio/alignments 地址", Modifier.fillMaxWidth())
        Text("使用当前转写密钥。留空不对齐；失败时保留切分文字及粗略时间。整体版仅提供上下文，不标注说话人或逐字时间。", style = RsType.caption, color = c.textSecondary)
        Pill("保存对齐地址", filled = false) {
            runCatching { settings.setAlignmentEndpoint(input) }.fold(
                { error = false; message = if (input.isBlank()) "已关闭时间戳对齐" else "对齐地址已保存" },
                { error = true; message = it.message ?: "地址无效" })
        }
        if (message.isNotEmpty()) Text(message, style = RsType.caption, color = if (error) c.critical else c.textSecondary)
        if (actual.isNotBlank()) Text(actual, style = RsType.caption, color = c.textSecondary)
    }
}
