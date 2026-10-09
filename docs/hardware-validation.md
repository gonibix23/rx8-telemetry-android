# Validación con el RX-8

## Objetivo

Confirmar la comunicación y disponibilidad real antes de prometer sensores o frecuencia.
Vehículo: RX-8 2004, 231 CV, Europa. Móvil: Galaxy A53. Adaptador: iLC B0BDRHRR22.

## Registro de prueba

- Fecha, versión de app/herramienta y versión Android.
- Identificador de sesión de prueba sin VIN completo.
- Modelo/revisión visible del adaptador y respuesta de identificación, sin asumir autenticidad por su texto.
- Tipo de Bluetooth y protocolo OBD detectado.
- Condiciones de encendido/motor y duración.
- PIDs soportados por la ECU, consultas efectuadas y latencia.
- Resultado del VIN: válido, ausente, error o no soportado; nunca adjuntar el VIN completo.
- Cortes, timeouts y comportamiento al reconectar.

## Secuencia de comprobación

1. Con el vehículo parado, comprobar emparejamiento y conexión del adaptador.
2. Diferenciar respuesta del adaptador de respuesta de la ECU.
3. Detectar el protocolo y consultar bloques de PIDs soportados.
4. Probar RPM y refrigerante; verificar velocidad cero con el coche parado.
5. Consultar VIN una vez y comprobar la reconstrucción de respuestas fragmentadas.
6. Mantener una sesión de al menos 10 minutos; medir cadencia efectiva y errores.
7. Bloquear pantalla, recuperar la app y comprobar continuidad de lo guardado.
8. Desconectar/reconectar Bluetooth y comprobar recuperación sin duplicados.
9. Repetir sin Internet.
10. Para validar velocidad en movimiento, usar una grabación ya iniciada o un acompañante; revisar los resultados después.

## Matriz de evidencia

| Dato | Documentado | Soportado anunciado | Lectura validada | Unidad | Observaciones |
| --- | --- | --- | --- | --- | --- |
| RPM | Sí, manual de primera serie | Pendiente | Pendiente | rpm | |
| Refrigerante | Sí, manual de primera serie | Pendiente | Pendiente | °C | |
| Velocidad | Sí, manual de primera serie | Pendiente | Pendiente | km/h | |
| VIN | Petición estándar; soporte opcional | Pendiente | Pendiente | texto | Sin registrar valor real aquí |
| Otros PIDs | Por investigar | Pendiente | Pendiente | Pendiente | |

## Resultado aceptable

Registrar tanto éxitos como incompatibilidades. Una respuesta `NO DATA` no demuestra que el sensor valga cero.
No sustituir una prueba física por capturas del simulador. Compartir solo registros anonimizados.
