package app.brujula.app

data class EduSection(val heading: String, val paragraphs: List<String>)

data class EduTopic(val id: String, val title: String, val summary: String, val sections: List<EduSection>)

/**
 * Contenido de la sección educativa. Los números de los ejemplos están comprobados contra el propio motor en
 * `EducationTest`: si cambian las reglas, esas pruebas avisan de que el texto hay que actualizarlo.
 */
object Education {

    val topics: List<EduTopic> = listOf(
        EduTopic(
            "como-funciona", "Cómo funciona Brújula", "Cuentas, datos y qué es (y qué no es) este simulador.",
            listOf(
                EduSection("Qué es", listOf(
                    "Brújula simula la experiencia de invertir en fondos indexados, ETF y Bitcoin con dinero ficticio, usando precios reales con retraso y la fiscalidad española.",
                    "Es una herramienta educativa. No ejecuta operaciones reales y no es asesoramiento financiero ni fiscal.",
                )),
                EduSection("Cuentas independientes", listOf(
                    "Cada cuenta tiene su propio efectivo, cartera, órdenes, historial, estadísticas y fiscalidad. No existe ninguna transferencia entre cuentas.",
                    "Al crear una cuenta indicas el saldo inicial y un ingreso mensual (con el día en que se abona). Si abres la app tras varios meses, se abonan todos los ingresos atrasados, una sola vez cada uno.",
                )),
                EduSection("Tus datos", listOf(
                    "Todo se guarda en tu móvil. Puedes hacer una copia de seguridad completa (Más › Copias de seguridad) y restaurarla si cambias de teléfono.",
                    "Internet solo se usa para descargar precios y tipos de cambio.",
                )),
            ),
        ),
        EduTopic(
            "fondos", "Fondos indexados", "Qué son, cómo se valoran y por qué los traspasos no tributan.",
            listOf(
                EduSection("Qué son", listOf(
                    "Un fondo indexado intenta copiar un índice (por ejemplo, el MSCI World) en lugar de intentar batirlo. Tiene costes bajos y mucha diversificación.",
                    "Al comprar, no compras a un precio que ves en pantalla: compras participaciones al valor liquidativo (VL) de un día de valoración, que se conoce al cierre.",
                )),
                EduSection("Cómo lo simula Brújula", listOf(
                    "Los fondos reales no tienen una serie de precios gratuita, así que Brújula usa como proxy un ETF que replica el mismo índice. Es una aproximación: no coincide exactamente con el fondo real.",
                    "Si creas la orden antes de las 14:00 (hora de Madrid) de un día hábil, se valora ese mismo día; si no, el siguiente día hábil. Nunca ves el valor liquidativo antes de crear la orden.",
                    "Cada compra, reembolso o traspaso de un fondo (indexado, monetario o mixto) tarda entre 1 y 2 días hábiles del mercado abierto en liquidarse, a elegir al azar en cada operación. Las participaciones compradas no se pueden vender hasta que se liquidan.",
                )),
                EduSection("Traspasos", listOf(
                    "Solo los fondos permiten traspasar el dinero a otro fondo sin pagar impuestos en ese momento: la ganancia se «arrastra» y tributa cuando reembolsas.",
                    "Los ETF y Bitcoin no tienen este régimen: venderlos tributa.",
                )),
            ),
        ),
        EduTopic(
            "etf", "ETF", "Fondos que cotizan en bolsa: precio continuo, compra inmediata y liquidación T+2.",
            listOf(
                EduSection("Qué son", listOf(
                    "Un ETF es un fondo que cotiza en bolsa como una acción. Su precio cambia durante la sesión y se negocia en el horario y los días hábiles de su mercado.",
                )),
                EduSection("Compra y venta en Brújula", listOf(
                    "Las órdenes a mercado de ETF se ejecutan de inmediato al último precio disponible: la cotización con retraso si el mercado está abierto o, si está cerrado, el último cierre. El efectivo se descuenta al instante.",
                    "Las órdenes limitadas y los stop-loss no son inmediatos: se comparan con los precios (apertura, mínimo, máximo) de cada sesión posterior a su creación.",
                    "La operación se liquida en T+2 en los mercados europeos (T+1 desde el 11 de octubre de 2027) y en T+1 en EE. UU. El dinero de una venta no está disponible hasta que se liquida.",
                )),
                EduSection("Acumulación o distribución", listOf(
                    "Un ETF de acumulación reinvierte los dividendos dentro del fondo y no tributa hasta que vendes. Uno de distribución te paga los dividendos en efectivo (no se reinvierten), y esos dividendos tributan cuando se cobran.",
                )),
            ),
        ),
        EduTopic(
            "bitcoin", "Bitcoin", "Un mercado abierto 24 horas, con compra inmediata y mucha volatilidad.",
            listOf(
                EduSection("Qué es", listOf(
                    "Bitcoin cotiza las 24 horas del día, los 7 días de la semana. Su precio es mucho más volátil que el de un índice bursátil.",
                )),
                EduSection("En Brújula", listOf(
                    "Las compras y ventas a mercado se ejecutan de inmediato al precio actual (con retraso) y se liquidan en el acto. Solo se compra y se vende con euros.",
                    "Fiscalmente, las ganancias y pérdidas de Bitcoin van a la base del ahorro y se calculan por FIFO. La regla de recompra de 2 meses no se aplica por defecto (es una opción en Más › Fiscalidad).",
                )),
            ),
        ),
        EduTopic(
            "diferencias", "Fondos, ETF y Bitcoin: diferencias", "Cuándo se ejecutan, cuándo se liquidan y cómo tributan.",
            listOf(
                EduSection("Resumen", listOf(
                    "Precio: fondo = valor liquidativo diario; ETF = cotización en bolsa; Bitcoin = cotización continua.",
                    "Ejecución a mercado: fondo = al valor liquidativo del día de valoración; ETF y Bitcoin = inmediata.",
                    "Liquidación: fondo = 1 o 2 días hábiles al azar; ETF = T+2 en Europa (T+1 desde octubre de 2027) y T+1 en EE. UU.; Bitcoin = inmediata.",
                    "Traspasos con diferimiento fiscal: solo los fondos.",
                    "Tipos de orden: los fondos solo admiten órdenes a mercado; ETF y Bitcoin admiten también limitadas y stop-loss.",
                    "Fiscalidad al vender: los tres generan ganancia o pérdida patrimonial en la base del ahorro; los dividendos cobrados de ETF de distribución son rendimientos del capital mobiliario.",
                )),
            ),
        ),
        EduTopic(
            "ordenes", "Órdenes", "A mercado, limitada, stop-loss, caducidad y estados.",
            listOf(
                EduSection("Tipos", listOf(
                    "A mercado: compras o vendes al precio disponible (ETF y Bitcoin al instante; fondos al valor liquidativo).",
                    "Limitada de compra: solo se ejecuta si el precio baja del límite. Limitada de venta: solo si sube por encima. Rozar el límite no basta: el precio debe superarlo.",
                    "Stop-loss (venta): protege de caídas. Se activa cuando el mínimo de la sesión llega al precio indicado; si el mercado abre por debajo, se ejecuta a la apertura.",
                    "Puedes poner caducidad (7, 30 o 90 días) o dejarla hasta cancelar. Las órdenes programadas (planes periódicos) compran un importe fijo o venden unidades fijas cada semana o cada mes.",
                )),
                EduSection("Reservas", listOf(
                    "Al aceptar una compra pendiente se reserva el efectivo (importe más comisión); al aceptar una venta o un traspaso se reservan las unidades. Así no se puede gastar dos veces el mismo dinero. Cancelar, caducar o rechazar libera la reserva.",
                )),
                EduSection("Ingresar dinero", listOf(
                    "Puedes añadir efectivo a una cuenta en cualquier momento (Inicio › Añadir dinero, o Más › Cuentas › Ingresar). Está disponible al instante y cuenta como aportación, igual que el saldo inicial y los ingresos mensuales: no distorsiona tu rentabilidad.",
                )),
                EduSection("Estados", listOf(
                    "Solicitada y aceptada → ejecutada → liquidada. Una orden también puede quedar cancelada, caducada o rechazada. Puedes ver la cronología de cada orden en Operaciones › Historial.",
                )),
            ),
        ),
        EduTopic(
            "liquidaciones", "Liquidaciones y dinero disponible", "Por qué el efectivo total y el disponible pueden diferir.",
            listOf(
                EduSection("Efectivo total y disponible", listOf(
                    "Efectivo disponible = efectivo − reservado en órdenes pendientes − ventas aún sin liquidar.",
                    "Ejemplo: vendes un ETF el lunes y se liquida el miércoles (T+2). El efectivo aparece en tu cuenta el lunes, pero no puedes gastarlo hasta el miércoles.",
                )),
                EduSection("Calendarios", listOf(
                    "Los días hábiles respetan los festivos de cada mercado (NYSE, Xetra, Euronext, Londres), incluidos Viernes Santo y Lunes de Pascua, así que una liquidación puede retrasarse.",
                )),
            ),
        ),
        EduTopic(
            "precios", "Cómo se actualizan los precios", "Fuentes, retrasos y qué pasa si algo falla.",
            listOf(
                EduSection("Actualización", listOf(
                    "Los precios se actualizan cada día a las 00:00 (hora de Madrid, con el cambio de horario). Si el móvil estaba apagado o Android retrasó la tarea, se recupera al abrir la app.",
                    "También puedes pulsar «Actualizar precios ahora» (Más › Diagnóstico). Al enviar una orden inmediata se refresca solo ese activo.",
                )),
                EduSection("Fuentes y límites", listOf(
                    "Los datos vienen de fuentes gratuitas: Yahoo Finance (no oficial, puede cambiar sin aviso), CoinGecko (solo 365 días de histórico) y el BCE (tipos de cambio).",
                    "Los precios tienen retraso y cada fondo usa un ETF proxy. Si un activo cotiza en dólares o libras, se convierte a euros con el tipo del BCE y siempre puedes ver la divisa original.",
                )),
                EduSection("Si falla una actualización", listOf(
                    "Nunca se inventan datos: se conserva el último precio válido y se muestra su antigüedad. Si falta el precio de un activo, su valor no entra en el patrimonio y se avisa.",
                )),
            ),
        ),
        EduTopic(
            "rentabilidad", "Rentabilidad y riesgo", "Cómo se calculan los periodos, la volatilidad y la clase de riesgo.",
            listOf(
                EduSection("Rentabilidad histórica", listOf(
                    "Se muestran YTD, 3 meses, 6 meses, 1, 2, 5 y 10 años y desde el inicio. Cada una compara el último cierre con el cierre del inicio del periodo: si un activo pasó de 100 a 110, la rentabilidad es +10,00 %.",
                    "Si la serie no tiene histórico suficiente para un periodo, aparece «Sin datos suficientes». Nunca se rellenan huecos con datos inventados.",
                )),
                EduSection("Riesgo", listOf(
                    "La volatilidad histórica es la desviación típica de las variaciones diarias del último año, anualizada. Se traduce en una clase de 1 (muy baja) a 7 (muy alta): menos de 0,5 %, 2 %, 5 %, 10 %, 15 % y 25 % marcan los saltos de clase.",
                    "Es un dato del pasado: no predice el futuro.",
                )),
            ),
        ),
        EduTopic(
            "fifo", "Precio medio y FIFO", "Cómo se calcula tu coste y qué participaciones se venden primero.",
            listOf(
                EduSection("Precio medio de compra", listOf(
                    "Es el coste total de lo que tienes dividido entre las participaciones. Si compras 10 u. a 100 € (1.000 €) y 10 u. a 120 € (1.200 €), tienes 20 u. por 2.200 €: precio medio 110 €. Las comisiones forman parte del coste.",
                )),
                EduSection("FIFO", listOf(
                    "Al vender, Hacienda considera que vendes primero las participaciones más antiguas (First In, First Out).",
                    "Ejemplo: con las dos compras anteriores vendes 15 u. a 130 € (1.950 €). Salen las 10 primeras (coste 1.000 €) y 5 de la segunda (coste 600 €): coste 1.600 €, ganancia 350 €.",
                    "Ese 350 € tributaría al 19 %: 66,50 €.",
                )),
            ),
        ),
        EduTopic(
            "impuestos", "Impuestos en España", "Qué tributa, cuándo se paga y cómo se calcula.",
            listOf(
                EduSection("Qué tributa", listOf(
                    "Las ganancias y pérdidas al vender (ETF, fondos, Bitcoin) y los dividendos cobrados van a la base del ahorro. Mientras no vendes, un fondo o ETF de acumulación no tributa.",
                )),
                EduSection("Escala del ahorro", listOf(
                    "19 % hasta 6.000 €, 21 % hasta 50.000 €, 23 % hasta 200.000 €, 27 % hasta 300.000 € y 30 % a partir de 300.000 €.",
                    "Ejemplo: una base de 500 € paga 95 €. Una base de 6.000 € paga 1.140 €.",
                )),
                EduSection("Pérdidas", listOf(
                    "Las pérdidas compensan primero las ganancias del año. Si sobran, compensan dividendos hasta el 25 % de estos; lo que queda se arrastra 4 años.",
                    "Ejemplo: pierdes 2.000 € y cobras 1.000 € de dividendos. Compensas 250 €, la base es 750 € y la cuota 142,50 €. Quedan 1.750 € por compensar en los 4 años siguientes.",
                    "Regla de recompra: si recompras lo mismo dentro de 2 meses (ETF) o 1 año (fondos) y sigues teniéndolo, la parte proporcional de la pérdida no cuenta hasta que lo vendas.",
                )),
                EduSection("Retenciones y calendario", listOf(
                    "Los dividendos y los reembolsos de fondos con ganancia sufren una retención del 19 % a cuenta, que se descuenta en la declaración.",
                    "El impuesto del año N se paga en la campaña de la renta de N+1: pago único el 30 de junio o fraccionado 60 % (30 de junio) y 40 % (5 de noviembre). Las devoluciones llegan, como mucho, 6 meses después de la campaña.",
                    "Si el efectivo no alcanza para pagar, queda en negativo: no se bloquea el pago ni se inventa dinero.",
                )),
                EduSection("Qué se simplifica", listOf(
                    "Se asume que el mínimo personal y familiar se aplica a otras rentas, no a la base del ahorro. No se simulan declaraciones complementarias. Es un simulador: no sustituye a un asesor.",
                )),
            ),
        ),
        EduTopic(
            "traspasos", "Traspasos entre fondos", "Cómo se difiere el impuesto y qué se conserva.",
            listOf(
                EduSection("Cómo funciona", listOf(
                    "En un traspaso vendes participaciones de un fondo y compras otro en el mismo movimiento. Al no pasar por tu cuenta, no tributa en ese momento.",
                    "Se conservan el coste y la fecha de adquisición de cada lote: cuando reembolses, el FIFO usará la fecha original.",
                    "Ejemplo: tienes 1.000 € de coste en el fondo A, valen 1.320 € y los traspasas al fondo B. No pagas nada. En el fondo B tu coste sigue siendo 1.000 €: la ganancia de 320 € tributará cuando reembolses.",
                    "Durante el traspaso (1 o 2 días hábiles al azar), las participaciones del fondo de destino no se pueden vender.",
                )),
            ),
        ),
        EduTopic(
            "estadisticas", "Cómo se calculan las estadísticas", "Rentabilidad total, ponderada por tiempo y comparación con un índice.",
            listOf(
                EduSection("Rentabilidades", listOf(
                    "Rentabilidad total: (patrimonio − aportado) / aportado. Es simple, pero no distingue cuándo aportaste el dinero.",
                    "Ponderada por tiempo: encadena las rentabilidades entre fechas descontando los ingresos. Es la que debes comparar con un índice. Ejemplo: +10 % en un periodo y +4,55 % en el siguiente (tras un ingreso) dan +15 % en total.",
                    "Anualizada: lleva esa rentabilidad a un año. Solo se muestra con al menos 30 días de datos.",
                )),
                EduSection("Comparación", listOf(
                    "Puedes comparar tu cuenta con MSCI World, S&P 500 u otros índices en el mismo periodo. El índice es un ETF proxy sin tus comisiones ni impuestos.",
                )),
            ),
        ),
        EduTopic(
            "limitaciones", "Limitaciones de la simulación", "Lo que Brújula simplifica o no puede garantizar.",
            listOf(
                EduSection("Datos", listOf(
                    "Los precios son de fuentes gratuitas, con retraso y sin garantía. Los fondos se aproximan con un ETF equivalente. Con datos diarios no se simula la hora exacta de ejecución ni el volumen.",
                )),
                EduSection("Operaciones", listOf(
                    "Las órdenes limitadas y los stop-loss se evalúan siempre del lado conservador. Los planes periódicos usan la regla de la siguiente sesión. Si falta el dato de mercado de una sesión, la orden espera.",
                )),
                EduSection("Fiscalidad", listOf(
                    "Los dividendos solo se admiten en activos cotizados en euros. Las fechas oficiales de la campaña de la renta solo están cargadas para 2026; en otros años son estimadas. Los traspasos entrantes no cuentan como recompra.",
                    "Los avisos de los modelos 720 y 721 son solo informativos.",
                )),
                EduSection("Importante", listOf(
                    "Rentabilidades pasadas no garantizan resultados futuros. Brújula es una herramienta de aprendizaje.",
                )),
            ),
        ),
    )

    fun byId(id: String): EduTopic? = topics.firstOrNull { it.id == id }
}
