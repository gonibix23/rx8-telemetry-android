# Privacidad y seguridad

## Datos del coche

El VIN, recorridos y registros completos pueden identificar un vehículo o sus hábitos de uso.
Usa datos sintéticos en ejemplos, simuladores, issues y pruebas. Oculta VIN, MAC Bluetooth y ubicaciones en registros compartidos.
No se solicita GPS en el MVP.

Las bases de datos se guardarán en almacenamiento privado de la app. La política de copia de seguridad, exportación y retención debe implementarse explícitamente antes de distribuirla.
Permite exportar y eliminar recorridos; muestra el espacio usado y evita borrar registros pendientes de envío sin indicarlo al usuario.

## Integración futura

La API estará desactivada hasta disponer de servidor, autenticación y consentimiento para enviar datos.
Usa HTTPS y credenciales fuera del repositorio. No envíes datos reales a un servidor de prueba por defecto.
Un UUID o un VIN no son credenciales ni autorizan acceso al vehículo en el servidor.

## Comunicar un problema

No publiques secretos o datos del coche en una issue. Contacta de forma privada con el propietario por un canal ya acordado.
Cuando GitHub permita informes privados de seguridad, se podrán usar desde la pestaña Security.
No se ha configurado una dirección de correo para avisos.

## Dependencias y distribución

Revisa dependencias y permisos. Los ficheros de firma, keystores, tokens y archivos locales de SDK no se versionan.
La versión inicial recopila información mediante consultas de lectura. No modifica la centralita.
