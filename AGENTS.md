# Pautas de trabajo para agentes

## Contexto y alcance

Lee README.md, docs/product.md, docs/architecture.md y la issue antes de modificar código.
Proyecto inicial: Mazda RX-8 2004 europeo, 231 CV; Samsung A53; iLC B0BDRHRR22.
Prioridad: datos en directo y registro local de recorridos. API preparada, sin backend desplegado.
El repositorio empieza como documentación y seguimiento; no inventes instrucciones de compilación ni pruebas ejecutadas.

## Implementación

- Mantén las capas de transporte Bluetooth, protocolo OBD, persistencia, presentación y sincronización separadas.
- Usa una cola de comandos OBD con cancelación, timeout y reintentos limitados.
- Descubre capacidades y conserva origen, unidad, tiempo y calidad de las muestras.
- Una lectura ausente o errónea no es cero. Señala las lecturas caducadas.
- No hardcodees un protocolo, PID propietario o frecuencia como compatible sin evidencia.
- UUID permanente por vehículo; VIN opcional. No identifiques el coche por el adaptador.
- Guarda localmente antes de intentar sincronizar. Solo confirma envío tras ACK.
- Los datos de simulador deben estar identificados y separados de los reales.
- No añadas control de actuadores, borrado de averías ni reprogramación al alcance actual.
- No subas VIN reales, tokens, claves de firma, bases de datos ni capturas sin anonimizar.

## Flujo y validación

- Implementa una issue por rama y mantén las PRs enfocadas.
- Actualiza documentación si cambia el alcance o el contrato.
- Ejecuta las comprobaciones pertinentes. Actualmente: npm ci y npm run lint:docs.
- Al introducir Android incorpora build, lint y pruebas significativas; no afirmes validación física con un simulador.
- Mantén claras las pruebas realizadas, las pendientes y las limitaciones.
- No marques una issue como completada hasta cumplir sus criterios.
- Conserva cambios ajenos y no hagas force-push, borrados o cambios de visibilidad por iniciativa propia.
