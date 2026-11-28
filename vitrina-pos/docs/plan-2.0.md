# Vitrina POS 2.0 — la nube

Estado: **2.0a–2.0d, 2.1, 2.2a y 2.2b hechas** (28/11/2026). 2.1: sesión con la cuenta del negocio (Iniciar sesión, varios negocios, Cambiar de usuario, Cerrar sesión de la cuenta). 2.2a: sucursales con inventario por sucursal (tablas branch y branchStock; product.stock es el total), equipo en una sucursal (lo decide el dueño en la nube), usuarios por sucursal y límite por plan. 2.2b: transferencias entre sucursales (tablas transfer y transferItem: sale al enviar, entra al recibir, "Llegó diferente", cancelar), reportes por sucursal y ticket con los datos de la sucursal. Siguen 2.3 (empleados con correo) y 2.0e (panel web). Servidor: `server/instalar.sh` (en el Droplet: `vitrina-actualizar`).

Propuesta visual: https://claude.ai/artifact/AfyLU9DEuaEVVWgQu6XZ9w

## Decisiones tomadas (26/11/2026)

| Tema | Decisión |
|---|---|
| Arquitectura | Una base de datos por negocio (el mismo motor y las mismas reglas de la app) + PostgreSQL administrado para cuentas, usuarios, equipos, sesiones y suscripciones. Servidor en un Droplet de DigitalOcean con volumen; fotos y respaldos nocturnos en Spaces. |
| Dirección | `api.facturaprord.online` para el servidor; `pos.facturaprord.online` para la versión de navegador (2.x). |
| Equipos | Cada equipo inicia sesión por su cuenta; desaparece el "equipo principal". Copia local y cola sin conexión en cada equipo. |
| Recuperar contraseña | Las dos vías: por correo (servicio de envío, por ejemplo Resend) **y** desde el panel administrativo del dueño de Vitrina. |
| Cobro | Por **plan con módulos** (ver abajo). |
| Factura Pro RD | Pendiente de revisar su repositorio para decidir si comparten cuentas. |

## Panel administrativo (dueño de Vitrina POS)

Página web privada solo para el dueño del servicio:

- Lista de negocios con su plan, estado (prueba, activo, vencido, suspendido), equipos y último uso.
- Restablecer la contraseña de una cuenta (genera un enlace o una contraseña temporal; queda en la actividad).
- Cambiar el plan, agregar o quitar módulos sueltos, extender la prueba, suspender o reactivar.
- Ver y revocar equipos de un negocio.
- Descargar el respaldo de un negocio.

## Planes con módulos

Cada negocio tiene **un plan** y, si quiere, **módulos extra** sueltos. El plan dice qué módulos están *disponibles*; el negocio decide cuáles *mostrar* (Configuración → Módulos, ya existe desde la 1.10). Así nadie queda sin acceso: un módulo que no está en su plan se puede agregar suelto.

### Módulos (claves en el sistema)

| Clave | Módulo |
|---|---|
| `ventas` | Vender, caja, catálogo, clientes, reportes (siempre incluido) |
| `inventario` | Compras con NCF, costo promedio, conteo, mermas, kardex, devoluciones |
| `taller` | Taller de reparación |
| `imei` | IMEI y número de serie |
| `variantes` | Variantes (color, capacidad, talla) |
| `cotizaciones` | Cotizaciones en PDF |
| `etiquetas` | Etiquetas con QR y precio |
| `facturacion` | Facturación electrónica (e-CF de la DGII) |
| `actividad` | Registro de actividad |
| `multiequipo` | Varios equipos y usuarios a la vez |

### Planes propuestos (precios por definir)

| | Esencial | Negocio | Pro |
|---|---|---|---|
| Equipos | 1 | 3 | 10 |
| Usuarios | 2 | 6 | ilimitados |
| ventas | ✓ | ✓ | ✓ |
| inventario | — | ✓ | ✓ |
| cotizaciones | — | ✓ | ✓ |
| etiquetas | ✓ | ✓ | ✓ |
| variantes | — | ✓ | ✓ |
| actividad | — | ✓ | ✓ |
| multiequipo | — | ✓ | ✓ |
| imei | — | — | ✓ |
| taller | extra | extra | ✓ |
| facturacion | extra | extra | ✓ |

Prueba gratis de 14 días con el plan Pro. Al vencer sin pago: 7 días de gracia; después la app queda en solo lectura (se puede consultar y respaldar, no vender), nunca se borran datos.

### Tablas (PostgreSQL)

- `plan(id, nombre, precio_mensual, precio_anual, max_equipos, max_usuarios, activo)`
- `plan_modulo(plan_id, modulo)`
- `cuenta(id, correo, hash_contrasena, nombre, creada)`
- `negocio(id, cuenta_id, nombre, tipo, base_archivo, creado)`
- `suscripcion(negocio_id, plan_id, estado, prueba_hasta, pagado_hasta, periodo)`
- `modulo_extra(negocio_id, modulo, precio_mensual, desde, hasta)`
- `equipo(id, negocio_id, nombre, plataforma, llave_hash, ultimo_uso, revocado)`
- `sesion(id, equipo_id, usuario, vence, renovada)`
- `restablecimiento(id, cuenta_id, token_hash, vence, usado, por_admin)`

El servidor calcula los módulos efectivos (plan + extras) y los envía a cada equipo al iniciar sesión; también los vuelve a revisar en cada operación.

## Etapas

1. **2.0a** Núcleo compartido (lógica separada de las pantallas).
2. **2.0b** Servidor: cuentas, equipos, sesiones, planes y módulos, permisos verificados, respaldos, Docker.
3. **2.0c** App: crear cuenta, iniciar sesión, autorizar equipo, panel de equipos, pasar datos a la nube.
4. **2.0d** Puesta en marcha en DigitalOcean y DNS; prueba con la tienda del dueño.
5. **2.0e** Panel administrativo web.

## Pendiente del dueño

- Precios de cada plan y de los módulos extra.
- Confirmar si el Taller va como módulo extra en Esencial y Negocio, o incluido en todos.
- Acceso al repositorio de Factura Pro RD.
- Cuenta de DigitalOcean (cuando se arranque 2.0d) y dónde está registrado el dominio.
