# Plan de desarrollo

Estado inicial: planificación. Ninguna capacidad del adaptador se considera validada físicamente todavía.

## Hitos e issues

| Código | Trabajo | Hito | Dependencias |
| --- | --- | --- | --- |
| RX8-001 | Comprobar compatibilidad con coche y A53 | M0 - Compatibilidad real | Ninguna |
| RX8-002 | Base Android y simulador | M1 - Conexion y adquisicion | Ninguna |
| RX8-003 | Transporte Bluetooth y permisos | M1 - Conexion y adquisicion | RX8-001, RX8-002 |
| RX8-004 | Lectura OBD y descubrimiento de capacidades | M1 - Conexion y adquisicion | RX8-003 |
| RX8-005 | Identidad permanente del vehículo | M2 - Datos y recorridos | RX8-004 |
| RX8-006 | Panel de datos en directo | M2 - Datos y recorridos | RX8-004, RX8-005 |
| RX8-007 | Grabación e historial local de recorridos | M2 - Datos y recorridos | RX8-005, RX8-006 |
| RX8-008 | Contrato y preparación de sincronización | M3 - Preparacion de API | RX8-007 |
| RX8-009 | Investigar sensores Mazda | Posterior - Extension Mazda | RX8-001, RX8-004 |

[Issues del proyecto](https://github.com/gonibix23/rx8-telemetry-android/issues) y [hitos](https://github.com/gonibix23/rx8-telemetry-android/milestones) son el seguimiento operativo.
Los códigos RX8-xxx permanecen estables aunque cambie el número de una issue.
RX8-001 y RX8-002 pueden avanzar en paralelo. Los fallos de conexión reales condicionan RX8-003 y RX8-004.

## Estado del repositorio

- Documentación inicial, formularios de issues y plantilla de PR.
- Comprobación automática de Markdown; aún no hay compilación Android.
- Integración prevista por squash y eliminación de ramas integradas.
- Protección de main: pendiente de verificar en GitHub al finalizar la configuración.
- No hay APK, backend, despliegue, release ni proyecto de GitHub Projects creado.

## Decisiones pendientes

- Versión Android del A53 y mínimo soportado.
- Transporte y protocolo realmente detectados.
- PIDs y cadencia sostenida con el adaptador concreto.
- Política y límite de retención local medidos con datos reales.
- Autenticación, alojamiento y motor de base de datos del servidor futuro.
- Licencia de distribución.

## Próximo resultado verificable

Una prueba corta documentada identifica Android, confirma comunicación con la ECU, registra capacidades y obtiene lecturas fiables.
No hace falta un backend para esa prueba.
