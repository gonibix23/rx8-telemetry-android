# Cómo contribuir

## Antes de empezar

Lee [el alcance](docs/product.md) y [la arquitectura](docs/architecture.md).
La prioridad inicial es datos en directo y recorridos del RX-8 europeo de 2004, 231 CV, en un Samsung A53.

## Issues

Usa el formulario de error, funcionalidad, tarea o prueba con hardware.
Una issue debe describir un resultado concreto e incluir:

- Problema o necesidad y resultado esperado.
- Alcance y exclusiones.
- Criterios de aceptación verificables.
- Dependencias mediante enlaces a otras issues.
- Evidencia y forma de comprobar el resultado.

Para fallos del coche registra versión de app/Android, adaptador, protocolo, PIDs y pasos de reproducción, con los identificadores personales ocultos.
Un dato pendiente de comprobar nunca se documenta como compatible.

## Etiquetas y prioridad

- Tipo: `type:bug`, `type:feature`, `type:task`, `type:research`.
- Área: `area:android`, `area:bluetooth`, `area:obd`, `area:data`, `area:ui`, `area:sync`, `area:docs`.
- Prioridad: `priority:P0` (pérdida de datos o bloqueo grave), `priority:P1` (necesario para MVP), `priority:P2` (mejora o fase posterior).
- Estado: una sola entre `status:triage`, `status:ready`, `status:in-progress`, `status:blocked`, `status:review`.
- Evidencia: `needs:hardware` cuando falta validación con coche/adaptador real.

Las issues completadas se cierran. No se sustituyen las dependencias por etiquetas: enlaza qué issue bloquea y por qué.
Las etiquetas de estado se actualizan manualmente; no hay un bot configurado.

## Ramas y commits

`main` contiene trabajo integrado. Después de la preparación inicial, trabaja en ramas cortas:

- `feat/12-live-dashboard`
- `fix/18-reconnect-timeout`
- `docs/21-api-contract`
- `chore/2-android-bootstrap`

Usa commits descriptivos: `feat(obd): decode coolant temperature`, `fix(data): preserve interrupted trips`.
Preferimos mensajes de tipo Conventional Commits, sin exigirlos mediante una automatización.
No subas claves, ficheros de firma, bases de datos reales ni exportaciones del coche.

## Pull requests

Abre una PR pequeña por objetivo. Puede ser borrador mientras falten pruebas.
Completa la plantilla: problema, comportamiento resultante, issue relacionada, validación, limitaciones y capturas cuando cambie la interfaz.

Usa `Closes #N` solo si se cumplen todos los criterios; para avances parciales usa `Refs #N`.
No cierres una issue de compatibilidad con una prueba de simulador.

Antes de integrar:

1. Revisa el diff completo y comprueba que no incluye datos personales ni cambios ajenos.
2. Ejecuta las comprobaciones que correspondan al cambio.
3. Explica cualquier prueba pendiente o limitación de hardware.
4. Solicita revisión si hay otra persona colaborando. En trabajo individual deja constancia de la revisión propia; no se exige una aprobación que el autor no puede darse.
5. Integra con squash y elimina la rama cuando ya no sea necesaria.

Las reglas documentadas no implican que GitHub las haga cumplir. El estado real de protección se registra en [el plan](docs/roadmap.md).

## Comprobaciones y definición de terminado

En esta fase ejecuta `npm ci` y `npm run lint:docs`.
Al crear el proyecto Android, añade compilación, lint Android y pruebas del parser, persistencia y sincronización al flujo de CI.
Prueba migraciones de base de datos y respuestas OBD fragmentadas. No añadas pruebas que se limiten a repetir la implementación.

Una tarea está terminada cuando cumple sus criterios, conserva el historial existente, documenta su comportamiento, tiene verificación suficiente y sus cambios están integrados.
Los cambios que dependen del coche deben registrar explícitamente si solo se han probado con simulador.

## Decisiones y releases

Registra decisiones importantes con contexto, alternativas y consecuencias en `docs/decisions/` cuando aparezcan.
Usa versiones `0.x.y` durante el desarrollo y notas de versión con cambios, compatibilidad comprobada y limitaciones.
No crees una release de APK hasta contar con una compilación real y pruebas de instalación.
