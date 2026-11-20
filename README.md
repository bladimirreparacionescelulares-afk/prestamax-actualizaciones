# PrestaMax · Actualizaciones oficiales

[Descargar la versión estable de PrestaMax](https://github.com/bladimirreparacionescelulares-afk/prestamax-actualizaciones/releases/latest/download/PrestaMax.apk)

Este repositorio distribuye únicamente el APK, sus novedades, información de versión firmada y comprobantes de descarga. No contiene el código fuente de la aplicación, bases de datos, copias de clientes, licencias ni claves privadas. Los archivos de automatización solo validan y publican las descargas.

## Aviso de seguridad pendiente

Google Play Protect bloqueó la versión 4.12 en al menos un teléfono como potencialmente dañina. La causa y el resultado de la revisión de Google siguen sin confirmarse. La versión 4.15 no supone una aprobación de Google. Las comprobaciones de firma e integridad no equivalen a una aprobación de Google. No desactives Play Protect ni fuerces la instalación mientras se revisa el aviso. Descargar este mismo APK desde GitHub no garantiza que desaparezca la advertencia.

## Versión 4.15

Primera etapa de correcciones de la revisión integral:

- Pérdidas de capital pendientes de recuperar, separadas de atrasos, gastos y capital perdonado; recuperaciones vinculadas sin contar dos veces el cobro.
- Historial de correcciones y anulaciones de cuentas simples y pagos fijos, con registro anterior y motivo. Consulta Seguridad y protección → Cambios y anulaciones.
- Archivado de cuentas simples y pagos fijos, con conservación de pagos y deudas y filtro Archivados.
- Tasas descritas con su base real e importes de gastos y estados de anulación correctos en la vista del negocio.
- Copias .mpbackup con mayor capacidad para adjuntos, comprobación de integridad y cifrado según el acceso o contraseña. Las copias anteriores siguen leyéndose; las nuevas requieren 4.15 o posterior.

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
