# RX-8 Telemetry Android

Aplicación Android prevista para consultar datos del coche en directo y registrar recorridos sin conexión a Internet.

**Estado: planificación y preparación del repositorio. Todavía no hay aplicación compilable, APK ni API desplegada.**

## Vehículo y equipo de referencia

- Mazda RX-8 de 2004, 231 CV, mercado europeo.
- Samsung Galaxy A53; versión de Android pendiente de comprobar.
- Adaptador iLC OBD2 Bluetooth, ASIN B0BDRHRR22. [Producto indicado](https://www.amazon.es/dp/B0BDRHRR22).
- Bluetooth clásico y comandos compatibles con ELM327 son la hipótesis inicial; deben comprobarse con la unidad real.

El objetivo es mostrar **todos los datos disponibles y verificados** en este coche. OBD2 no garantiza acceso a todos los módulos o sensores.

## Primera versión

- Conectar el adaptador y descubrir protocolo y parámetros compatibles.
- Panel de datos en directo con unidades y estado de cada lectura.
- Perfil de coche con ID permanente y bastidor (VIN) opcional.
- Inicio y fin manual de recorridos, guardado local, historial, gráficas y exportación.
- Grabación con pantalla bloqueada, gestionada como sesión explícita.
- Preparación de datos y cola para una API futura, con los envíos desactivados.

Diagnóstico de averías, sensores específicos Mazda, GPS/mapas y servidor quedan para fases posteriores. La primera versión no incorpora control de actuadores, reprogramación ni borrado de averías.

## Documentación

| Documento | Contenido |
| --- | --- |
| [Producto](docs/product.md) | Alcance, pantallas y criterios de aceptación |
| [Arquitectura](docs/architecture.md) | Android, Bluetooth, OBD, almacenamiento y sincronización |
| [Contrato de API](docs/api-contract.md) | Diseño de integración futura; no es un servicio existente |
| [Plan de desarrollo](docs/roadmap.md) | Fases, prioridades y dependencias |
| [Prueba con el coche](docs/hardware-validation.md) | Evidencia necesaria para confirmar compatibilidad |
| [Contribución](CONTRIBUTING.md) | Issues, ramas, PRs, revisión y releases |
| [Pautas para agentes](AGENTS.md) | Reglas de implementación y verificación |
| [Privacidad y seguridad](SECURITY.md) | Tratamiento de VIN, registros y credenciales |

## Seguimiento

- [Issues](https://github.com/gonibix23/rx8-telemetry-android/issues)
- [Hitos](https://github.com/gonibix23/rx8-telemetry-android/milestones)
- [Nueva issue](https://github.com/gonibix23/rx8-telemetry-android/issues/new/choose)
- [Pull requests](https://github.com/gonibix23/rx8-telemetry-android/pulls)

Cada issue tiene alcance y criterios de aceptación. Los cambios de implementación se realizan mediante PR vinculada a una issue.

## Comprobar este repositorio

Requisitos para comprobar documentación: Node.js 22 o posterior y npm.

```sh
npm ci
npm run lint:docs
```

Estas comprobaciones revisan documentación. No compilan Android ni verifican el adaptador. La base Android, su instalación y sus pruebas se incorporarán en RX8-002.

## Licencia

Repositorio privado. No se ha seleccionado una licencia de distribución.
