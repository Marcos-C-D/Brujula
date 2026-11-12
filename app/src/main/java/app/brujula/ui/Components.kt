package app.brujula.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brujula.app.Fmt
import app.brujula.app.Slice
import java.math.BigDecimal

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val base = modifier.fillMaxWidth().padding(vertical = 4.dp)
    Card(modifier = if (onClick != null) base.clickable { onClick() } else base) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

/** Verde si es positivo, rojo si es negativo. */
fun signColor(positive: Boolean?): Color = when (positive) {
    true -> Esmeralda
    false -> Rojo
    null -> Color.Unspecified
}

@Composable
fun Notice(text: String) {
    Text(
        "⚠ $text",
        style = MaterialTheme.typography.bodySmall,
        color = Rojo,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Gráfico de línea sencillo. Los valores nulos (sin dato) cortan la línea: no se inventa nada. */
@Composable
fun LineChart(values: List<Double?>, modifier: Modifier = Modifier.fillMaxWidth().height(160.dp), color: Color = Esmeralda) {
    val known = values.filterNotNull()
    if (known.size < 2) {
        Text("Aún no hay datos suficientes para el gráfico", style = MaterialTheme.typography.bodySmall)
        return
    }
    val min = known.min()
    val max = known.max()
    val range = if (max - min < 1e-9) 1.0 else max - min
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stepX = if (values.size > 1) w / (values.size - 1) else w
        var path = Path()
        var open = false
        values.forEachIndexed { i, v ->
            if (v == null) { open = false; return@forEachIndexed }
            val x = i * stepX
            val y = (h - ((v - min) / range * h * 0.9 + h * 0.05)).toFloat()
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
        drawLine(color.copy(alpha = 0.25f), Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)
    }
}

/** Barras horizontales de reparto (porcentaje del total). */
@Composable
fun AllocationBars(slices: List<Slice>) {
    val colors = listOf(Esmeralda, Color(0xFF4F8FE0), Color(0xFFE0A84F), Color(0xFFB56FD9), Color(0xFFE5645A), Color(0xFF6FC3D9), Color(0xFF9AA5A0))
    slices.forEachIndexed { i, s ->
        val fraction = (s.percent.toFloat() / 100f).coerceIn(0.01f, 1f)
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.label, style = MaterialTheme.typography.bodySmall)
                Text("${Fmt.pct(s.percent)} · ${Fmt.eur(s.value)}", style = MaterialTheme.typography.bodySmall)
            }
            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Gray.copy(alpha = 0.2f))) {
                Box(modifier = Modifier.fillMaxWidth(fraction).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors[i % colors.size]))
            }
        }
    }
}

fun BigDecimal?.sign(): Boolean? = this?.signum()?.let { if (it > 0) true else if (it < 0) false else null }
