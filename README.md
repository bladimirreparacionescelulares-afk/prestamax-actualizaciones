# PrestaMax · Actualizaciones oficiales

[Descargar la versión estable de PrestaMax](https://github.com/bladimirreparacionescelulares-afk/prestamax-actualizaciones/releases/latest/download/PrestaMax.apk)

Este repositorio distribuye únicamente el APK, sus novedades, información de versión firmada y comprobantes de descarga. No contiene el código fuente de la aplicación, bases de datos, copias de clientes, licencias ni claves privadas. Los archivos de automatización solo validan y publican las descargas.

## Aviso de seguridad pendiente

Google Play Protect bloqueó la versión 4.12 en al menos un teléfono como potencialmente dañina. La causa y el resultado de la revisión de Google siguen sin confirmarse. La versión 4.16.2 no supone una aprobación de Google. Las comprobaciones de firma e integridad no equivalen a una aprobación de Google. No desactives Play Protect ni fuerces la instalación mientras se revisa el aviso. Descargar este mismo APK desde GitHub no garantiza que desaparezca la advertencia.

## Versión 4.16.2

- Caja es opcional y viene desactivada por defecto, también si una instalación anterior no tenía guardada esta preferencia. Se muestra desde Configuración → Módulos → Caja. No reaparece el acceso duplicado «Clientes y préstamos».
- Ocultar una Caja configurada conserva su historial y mantiene el registro de cobros, pagos, desembolsos y gastos vinculados. Ahorros mantiene su interruptor independiente.
- El propietario puede elegir un logo de su espacio desde Configuración → Impresora Bluetooth → Cambiar logo. Si no se elige uno, los tickets pequeños imprimen el de PrestaMax. La vista previa y el envío ESC/POS usan la misma imagen en blanco y negro, centrada y sin recortar.
- Cada actualización muestra sus novedades al abrir la aplicación, después del acceso correspondiente. No necesita conexión a internet. Se pueden consultar de nuevo desde Configuración → Información → Novedades de esta versión.
- Las nuevas copias incluyen la preferencia de Caja y el logo del espacio y requieren 4.16.2 o posterior. Las copias anteriores siguen admitiéndose.

Caja conserva el efectivo y el banco separados por moneda y espacio, movimientos vinculados sin duplicación, aportes y retiros documentados, cierres que preservan su historial y revisión de movimientos anteriores a la apertura. Banco es un registro manual sin conexión bancaria. Las diferencias del cierre no registran automáticamente pérdidas de préstamos.

Continúan incluidas las correcciones de pérdidas, recuperaciones, archivado e historial de cambios de 4.15. Los ahorros mantienen su registro independiente; un traslado hacia o desde Caja requiere registrar el retiro o aporte correspondiente.

Los tickets Bluetooth y la organización del formulario y de Configuración de la versión anterior continúan incluidos. La prueba con impresora física sigue pendiente.

Incluye los espacios personales y de negocio cifrados y permisos locales de 4.13. Sus copias son manuales y los vencimientos se consultan al abrir cada espacio; los espacios cerrados no generan avisos de sistema. La sincronización entre teléfonos sigue pendiente.

## Actualizar

Conserva tu copia de seguridad habitual. Abre el APK y elige actualizar la instalación existente, sin desinstalar ni borrar datos. La versión 4.11 se instala inicialmente desde este enlace; a partir de ella, PrestaMax puede avisar de nuevas versiones y descargarlas desde la propia app. Android siempre solicita confirmar la instalación y, la primera vez, puede pedir autorizar esta fuente.

Descargar el APK no concede una licencia. La autorización de uso continúa vinculada al teléfono y a las condiciones que gestione quien te entregó la licencia.

## Comprobaciones

Cada release incluye `PrestaMax.apk`, `actualizacion.json` y `SHA256SUMS.txt`. El anuncio de versión está firmado con la clave publicadora, que se conserva fuera de GitHub. La app verifica esa firma, el contenido descargado y el certificado de instalación. Los usuarios no necesitan iniciar sesión ni conceder acceso a sus cuentas de GitHub.

Certificado SHA-256 de las entregas APK de este canal:

`dbbe6a521eb39f1234f12811810d5775d8fed45b5160840ec58cf7e719551414`

La edición de Google Play gestiona las actualizaciones mediante la tienda. Este canal corresponde a la distribución APK.
