package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** 节点按需展开，避免轨迹数组一次创建数千个 Compose 节点；不丢弃未识别字段。 */
@Composable
internal fun LepaoRunFields(name: String, value: Any?, level: Int = 0) {
    val composite = value is JSONObject || value is JSONArray
    var expanded by remember(name, value) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = if (level == 0) 0.dp else 8.dp)) {
        if (composite) {
            val count = when (value) { is JSONObject -> value.length(); is JSONArray -> value.length(); else -> 0 }
            TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "−" else "+"} $name [$count]", fontSize = 11.sp) }
            if (expanded) {
                when (value) {
                    is JSONObject -> value.keys().asSequence().toList().sorted().forEach { key ->
                        LepaoRunFields(key, value.opt(key), level + 1)
                    }
                    is JSONArray -> {
                        var shown by remember(value) { mutableStateOf(50) }
                        for (i in 0 until minOf(shown, value.length())) LepaoRunFields("[$i]", value.opt(i), level + 1)
                        if (shown < value.length()) TextButton(onClick = { shown += 50 }) { Text("+ 50") }
                    }
                }
            }
        } else {
            Text("$name: ${if (value == null || value == JSONObject.NULL) "null" else value}",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
