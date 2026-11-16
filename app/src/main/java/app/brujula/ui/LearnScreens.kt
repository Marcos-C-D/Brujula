package app.brujula.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.brujula.app.Education

@Composable
fun LearnScreen(c: UiController) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Guía de Brújula")
        Text(
            "Cómo funcionan los fondos, los ETF y Bitcoin, las órdenes, las liquidaciones, los precios, las rentabilidades y los impuestos en España, con ejemplos numéricos.",
            style = MaterialTheme.typography.bodySmall,
        )
        Education.topics.forEach { t ->
            InfoCard(onClick = { c.sub.value = SubScreen.Topic(t.id) }) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Text(t.summary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
fun TopicScreen(id: String) {
    val topic = Education.byId(id)
    if (topic == null) {
        Text("Este tema ya no existe.", modifier = Modifier.padding(16.dp))
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Text(topic.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        topic.sections.forEach { s ->
            SectionTitle(s.heading)
            s.paragraphs.forEach { p ->
                Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Text(
            "Brújula es una herramienta educativa: no es asesoramiento financiero ni fiscal.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
    }
}
