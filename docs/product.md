# Producto y alcance

## Contexto confirmado

Usuario con Mazda RX-8 de 2004, 231 CV, mercado europeo; Samsung Galaxy A53; Android pendiente de identificar.
Adaptador del usuario: iLC OBD2 Bluetooth, ASIN B0BDRHRR22.
Prioridad elegida: datos en directo y registro de recorridos. Preparar la futura API, sin desarrollarla todavía.

## Resultado esperado del MVP

El usuario selecciona el adaptador y el perfil de coche, conecta, ve los datos disponibles e inicia un recorrido.
Puede bloquear la pantalla durante la grabación, detenerla desde una notificación y consultar el historial sin Internet.

## Pantallas previstas

| Pantalla | Contenido |
| --- | --- |
| Conexión y vehículo | Adaptador, permisos, estado, perfil, alias y VIN opcional |
| Datos en directo | RPM, refrigerante, velocidad y favoritos entre los sensores compatibles |
| Recorridos | Sesiones, duración, gráficas, huecos de conexión y resumen |
| Detalle de recorrido | Muestras, mínimos/máximos válidos, exportar y eliminar |
| Ajustes | Unidades, frecuencia/prioridades, almacenamiento y estado de sincronización |

No se incluyen mapas en el MVP. Un recorrido es una sesión de telemetría.
La distancia calculada desde velocidad debe indicarse como estimación y no como odómetro del coche.

## Datos candidatos y límites

El manual de taller de la primera serie enumera RPM, velocidad, refrigerante, admisión, carga, MAF, mariposa, ajustes de combustible y avance.
Es una lista candidata: cada parámetro debe confirmarse mediante capacidades y lecturas del coche real.

El VIN se intentará leer mediante modo 09, PID 02; su ausencia permite continuar.
El kilometraje total, temperatura/presión de aceite y otros datos Mazda no se garantizan por OBD genérico.
Un interruptor de presión de aceite no proporciona presión continua en bar.
La compresión requiere una medición externa, no se ofrecerá como lectura OBD.
ABS/SRS no forman parte de la compatibilidad prometida por este adaptador.

## Criterios de aceptación del MVP

- Funciona sin Internet y sin API.
- Distingue conexión Bluetooth de comunicación efectiva con la ECU.
- Descubre los parámetros accesibles y no muestra errores como cero.
- Conserva vehículo, sesiones y muestras entre reinicios.
- Añadir un VIN posteriormente no divide ni pierde el historial.
- Mantiene grabación iniciada explícitamente con pantalla bloqueada en el A53.
- Tras una interrupción conserva lo ya guardado y señala los huecos.
- Exporta y elimina recorridos con alcance claro.
- Permite demostrar flujos con datos sintéticos claramente identificados.
- Prepara lotes para el contrato futuro sin realizar envíos reales.

## Fuera de esta primera versión

API/servidor/base de datos remota, usuarios en la nube, mapas GPS, diagnóstico avanzado, borrado de averías, reprogramación y control de actuadores.
Los sensores Mazda adicionales requieren investigación y pruebas específicas antes de incorporarse.

## Referencias

Consultadas el 10 de octubre de 2026. Los manuales Mazda están reproducidos en sitios de terceros.

- [Producto, mismo ASIN en Amazon Italia](https://www.amazon.it/iLC-Bluetooth-Scansione-Collegare-Dispositivi/dp/B0BDRHRR22).
- [Mazda: modos de diagnóstico](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T02.html).
- [Mazda: monitor de parámetros](https://www.foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T05.html).
- [Mazda: cuadro, ODOCOUNT y OPSC](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE092255430T06.html).
- [Mazda: inspección de compresión](https://rx8handbuch.de/esicont/en/srvc/html/BHE011002000W03.html).
- [ELM327: comandos y respuestas VIN](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf).
