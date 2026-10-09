# Contrato propuesto de API v1

## Estado y límites

Diseño para preparar la app. No existe API, URL real, autenticación configurada ni base de datos remota.
RX8-008 implementará modelos, interfaz y pruebas aisladas; el backend se hará en otra fase.
Todos los ejemplos son sintéticos. Diagnóstico de averías y eliminación remota quedan fuera de este contrato inicial.

## Convenciones

HTTPS/JSON; rutas /v1; schemaVersion igual a 1; fechas UTC ISO 8601 terminadas en Z.
UUID generados por el cliente y conservados para vehículo, sesión, muestra y lote.
Unidades canónicas explícitas, sin depender del idioma del móvil.
VIN opcional; ni VIN ni UUID son una credencial. La autorización debe comprobar pertenencia de cada recurso.

## Recursos previstos

| Método | Ruta | Propósito |
| --- | --- | --- |
| PUT | /v1/vehicles/{vehicleId} | Crear o actualizar metadatos del vehículo |
| PUT | /v1/sessions/{sessionId} | Crear o actualizar recorrido |
| POST | /v1/telemetry/batches | Guardar muestras por lotes |

Confirmar primero vehículo, después sesión y por último muestras.
No se realizan solicitudes mientras no haya servidor configurado.

## Vehículo y sesión

Vehículo: vehicleId, revision, vin nullable, vinSource (obd/manual/null), alias, make, model, modelYear, market y powerCv.
Los metadatos del RX-8 los confirma el usuario; no se presupone que los entregue OBD.
Añadir VIN conserva vehicleId.

Sesión: sessionId, vehicleId, revision, startedAt, endedAt nullable, durationMs nullable, status, appVersion, obdProtocol nullable y dataOrigin (real/simulated).
Estados: recording, completed, interrupted.
La duración usa reloj monotónico; los cambios del reloj de calendario no deben producir duraciones negativas.

revision es un entero positivo que aumenta con cada cambio:

- Superior a la almacenada: aplicar.
- Igual y mismo contenido: confirmar sin duplicar.
- Igual con otro contenido: conflicto.
- Inferior: rechazar como desactualizada.

Esto presupone un solo escritor por vehículo; concurrencia entre dispositivos es una ampliación posterior.
El cierre aumenta revision para evitar que un reintento antiguo reabra un recorrido.

## Lote de telemetría

Un lote pertenece a un único vehículo y sesión. Su cuerpo queda inmutable al intentar enviarlo.
La cabecera Idempotency-Key debe coincidir con batchId.

```json
{
  "schemaVersion": 1,
  "batchId": "0dfd5cf5-c8dc-40d1-9b86-d38d55afba9a",
  "vehicleId": "17ca18ac-8df9-4af1-8d78-b44759c6e993",
  "sessionId": "af32b50c-4c11-40ea-bd9b-a6ebc46d236d",
  "samples": [
    {
      "sampleId": "8463ab69-1c02-4c93-a86a-d4bf580f1378",
      "sequence": 1,
      "observedAt": "2026-10-10T09:00:01.240Z",
      "elapsedMs": 1240,
      "metric": "engine_rpm",
      "value": 850,
      "unit": "rpm",
      "status": "ok",
      "source": "obd_standard",
      "service": "01",
      "pid": "0C",
      "ecuAddress": null
    }
  ]
}
```

sampleId es único; sequence crece dentro de la sesión y se conserva tras reintentos.
Cada muestra tiene su propio instante. service, pid y ecuAddress son opcionales.
status admite ok, timeout, unsupported y parse_error. Si no es ok, value es null.
Una lectura caducada es un estado de presentación, no una nueva muestra del último valor.

source admite obd_standard, obd_vendor, adapter y derived.
Una magnitud calculada documenta fórmula y versión antes de incorporarse.
La tensión medida por el adaptador usa una métrica distinta de la informada por la ECU.

| Magnitud | Unidad |
| --- | --- |
| Régimen | rpm |
| Velocidad | km/h |
| Temperatura | Cel |
| Porcentaje | % |
| Presión | kPa |
| Caudal de aire | g/s |
| Tensión | V |

El tamaño máximo de lote se fijará y probará con el backend. Debe ser configurable y permitir división.
No se envían datos simulados como sesiones reales.

## Confirmación y duplicados

Respuesta propuesta HTTP 200:

```json
{
  "schemaVersion": 1,
  "batchId": "0dfd5cf5-c8dc-40d1-9b86-d38d55afba9a",
  "results": [
    {
      "sampleId": "8463ab69-1c02-4c93-a86a-d4bf580f1378",
      "status": "stored"
    }
  ]
}
```

Estados por muestra: stored, already_stored o rejected. Un rechazo añade code y message.
Solo stored y already_stored confirman persistencia duradera. HTTP 200 sin esos resultados no confirma el lote.

El servidor deduplica por sampleId dentro del ámbito autorizado, incluso entre lotes distintos.
Reutilizar sampleId con otro contenido o Idempotency-Key con otro cuerpo produce conflicto.
Nunca sobrescribir una muestra existente por un reintento.

Si se pierde la respuesta, reenviar el mismo lote inmutable.
Si hay ACK parcial, guardar confirmaciones atómicamente y crear un nuevo lote con lo pendiente que admita reintento.
Los sampleId desconocidos o repetidos de una respuesta no se aceptan ciegamente.
Una muestra omitida sigue pendiente; un rechazo permanente queda bloqueado para revisión.

## Cola local

Estados: pending, sending, acknowledged, blocked.
Guardar muestra y trabajo pendiente en una misma transacción.
Registrar intentos, último error, siguiente intento y una reclamación temporal para evitar workers duplicados.
Una reclamación caducada tras terminar el proceso permite recuperar trabajos atascados en sending.

Mientras no exista servidor la sincronización se muestra desactivada y no genera intentos de conexión.
Las pruebas usan almacenamiento aislado; sus ACK no modifican registros reales.

## Errores y retención

| Caso | Acción |
| --- | --- |
| Sin conexión, timeout, 5xx | Espera creciente con variación aleatoria y reintento |
| 429 | Respetar Retry-After cuando exista |
| 401/403 | Pausar hasta resolver autenticación o acceso |
| 409 | Conservar datos y resolver conflicto |
| 413 | Crear lotes más pequeños con nuevos batchId |
| Validación permanente | Bloquear muestra afectada, conservar localmente |

Sincronizar no elimina el historial local.
Exportar incluye registros pendientes y bloqueados.
Eliminar localmente retira trabajos que los referencian y no implica borrar en un servidor futuro.

## Verificación prevista

Reenvío idéntico; respuesta perdida; ACK parcial; misma muestra en otro lote; conflictos; recuperación de sending; VIN añadido después; revisión antigua; sesión interrumpida; errores de autorización; exportación sin servidor.
Estas pruebas están pendientes de implementación en RX8-008.
