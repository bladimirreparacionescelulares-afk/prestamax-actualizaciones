# PrestaMax · Actualizaciones oficiales

[Descargar la versión estable de PrestaMax](https://github.com/bladimirreparacionescelulares-afk/prestamax-actualizaciones/releases/latest/download/PrestaMax.apk)

Este repositorio distribuye únicamente el APK, sus novedades, información de versión firmada y comprobantes de descarga. No contiene el código fuente de la aplicación, bases de datos, copias de clientes, licencias ni claves privadas. Los archivos de automatización solo validan y publican las descargas.

## Aviso de seguridad pendiente

Google Play Protect bloqueó la versión 4.12 en al menos un teléfono como potencialmente dañina. La causa y el resultado de la revisión de Google siguen sin confirmarse. La versión 4.16.1 no supone una aprobación de Google. Las comprobaciones de firma e integridad no equivalen a una aprobación de Google. No desactives Play Protect ni fuerces la instalación mientras se revisa el aviso. Descargar este mismo APK desde GitHub no garantiza que desaparezca la advertencia.

## Versión 4.16.1

Caja y cierre diario desde Inicio, en lugar del acceso duplicado «Clientes y préstamos». La pestaña Préstamos mantiene sus funciones.

- Apertura con el efectivo y dinero disponible en banco, por moneda y espacio. Banco es un registro manual sin conexión bancaria.
- Cobros, pagos, desembolsos confirmados, gastos reales de préstamos y pagos fijos vinculados a Caja una sola vez. Las deudas anteriores por pagar no agregan dinero sin confirmación expresa.
- Aportes, retiros, gastos de Caja y traspasos Efectivo/Banco con motivo y confirmación. Las pérdidas, condonaciones e intereses pendientes no mueven dinero.
- Correcciones y anulaciones mediante diferencias, sin borrar el movimiento original ni modificar los cierres guardados. Los registros históricos que lo requieran se revisan antes de incluirse.
- Los movimientos anteriores al momento exacto de la apertura se revisan antes de incluirse, también si ocurrieron ese mismo día. La revisión muestra la hora de apertura y la fecha de origen para evitar contar dinero dos veces.
- Cierre con saldo esperado, importe contado o confirmado y explicación de diferencias. La diferencia no registra automáticamente una pérdida de préstamo.
- Caja cifrada y reservada al propietario; los empleados y cobradores conservan sus permisos para registrar operaciones, sin ver los saldos del propietario. En Registros anteriores se configura un PIN específico de Caja.
- Copias con Caja cifrada y verificación después de restaurar. Las nuevas copias requieren la versión 4.16 o posterior. Una copia sin Caja debe restaurarse en otro espacio si ya existe un historial de Caja que conservar.

Continúan incluidas las correcciones de pérdidas, recuperaciones, archivado e historial de cambios de la versión 4.15. No se añaden pérdidas automáticas. Los ahorros mantienen su registro independiente; un traslado de dinero hacia o desde Caja requiere registrar el retiro o aporte correspondiente.

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
