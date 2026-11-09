# Datos de mercado (fase 2)

## Fuentes (todas gratuitas)
| Fuente | Uso | Limitaciones |
|---|---|---|
| Yahoo Finance (endpoint de gráficos) | Histórico diario de ETF, índices proxy y BTC-EUR | **No oficial**: puede cambiar o limitar sin aviso; términos de uso personal |
| CoinGecko (API pública) | Respaldo de Bitcoin | Histórico gratuito limitado a 365 días; límites de peticiones |
| BCE (servicio de datos) | Tipos de cambio diarios a EUR | Solo divisas publicadas por el BCE |

La capa de datos es intercambiable: un proveedor se sustituye sin tocar el resto.

## Reglas
- **Nunca se inventan datos.** Si no hay precio, no se muestra; si no hay histórico suficiente, «Sin datos suficientes».
- Si falla una actualización se conserva el último precio válido y se muestra su antigüedad.
- Un activo mantiene su proxy guardado: solo cambia de símbolo si no hay datos, o si lleva más de 14 días sin
  actualizarse, y en ese caso la serie se sustituye entera (nunca se mezclan precios de ETF distintos).
- Una serie nueva sospechosamente más corta que la guardada se rechaza.
- Los precios se convierten a EUR con el tipo del BCE del día (o el último publicado, máx. 7 días); siempre se
  conserva la divisa original.

## Rentabilidad y riesgo
- Rentabilidad acumulada entre el último cierre y el cierre igual o anterior al inicio del periodo (YTD: último cierre
  del año anterior). Si todos los puntos traen cierre ajustado se usa ese (incluye dividendos).
- Volatilidad: desviación típica anualizada de los rendimientos logarítmicos diarios (×√252). Clase de riesgo 1-7 con los
  tramos del indicador UCITS (SRRI): <0,5 · <2 · <5 · <10 · <15 · <25 · ≥25 %. Es histórica, no una predicción.

## Fondos, ETF y Bitcoin
No son lo mismo: los fondos indexados se muestran con el **proxy** de un ETF que replica el índice (no el valor
liquidativo real); los ETF cotizan en su mercado y calendario; Bitcoin cotiza 24/7.

## Actualización
Cada día a las 00:00 de Europe/Madrid (WorkManager, reprogramándose). Si Android la retrasa o el móvil estaba apagado,
se recupera al abrir la app. El botón manual solo actualiza datos de mercado.

## Estado de verificación
Los símbolos de proxy del catálogo son candidatos **sin verificar** desde el entorno de desarrollo: el botón de
diagnóstico indica cuáles responden. Los analizadores de cada proveedor están probados con respuestas de ejemplo,
no contra los servidores reales.
