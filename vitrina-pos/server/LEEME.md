# Servidor de Vitrina POS (2.0b)

Servidor en la nube: cuentas, equipos, sesiones, planes con módulos, permisos verificados y una base por negocio.
Usa el mismo núcleo (`:core`) que la app, así que las reglas del negocio (caja, stock, NCF, IMEI, devoluciones…) son las mismas.

## Cómo está hecho

| Pieza | Dónde |
|---|---|
| Cuentas, negocios, planes, suscripciones, equipos, códigos de recuperación | PostgreSQL (`src/main/resources/db/V1__inicial.sql`) |
| Datos de cada negocio (ventas, inventario, caja, e-CF…) | Un archivo por negocio en `DATA_DIR/negocio-N/negocio.db` |
| Respaldos | Cada noche a las 3:00 a. m. (hora de RD) en `DATA_DIR/respaldos/negocio-N/AAAA-MM-DD.db`, 30 días |
| Fotos | `DATA_DIR/negocio-N/images` |

## Sesiones

1. **El dueño autoriza un equipo** con su correo y contraseña → el equipo recibe una llave propia (`deviceId` + `deviceSecret`).
2. **El equipo pide una ficha** de 1 hora con su llave (`/v1/sesion/ficha`). Si el dueño quita el equipo, deja de recibirla.
3. **Cada empleado entra con su PIN** en un equipo autorizado (`/v1/sesion/usuario`) → ficha de usuario de 8 horas.
4. Cada operación lleva la ficha del equipo y la del usuario. El servidor revisa el estado del plan, el módulo, el permiso del usuario,
   y anota en la actividad el usuario y el equipo **verificados** (no los que diga el equipo).

## API (resumen)

| Método y ruta | Para qué |
|---|---|
| `GET /salud` | ¿Está vivo? |
| `POST /v1/cuentas` | Crear cuenta + negocio + administrador (+ autorizar el equipo) |
| `POST /v1/sesion/equipo` | Autorizar un equipo con correo y contraseña |
| `POST /v1/sesion/ficha` | Ficha del equipo (1 h) con su plan y módulos |
| `POST /v1/sesion/usuario` | Entrar con el PIN |
| `POST /v1/sesion/dueno` | Sesión del dueño (30 min) para administrar equipos |
| `POST /v1/operaciones` | Aplicar una operación (idempotente con `opId`) |
| `GET /v1/cambios?desde=N&esperar=1` | Cambios para mantener la copia local al día |
| `GET/PUT /v1/fotos/{nombre}` | Fotos de productos y del taller |
| `GET /v1/equipos`, `POST /v1/equipos/{id}/quitar` | Equipos del negocio |
| `POST /v1/contrasena/olvide` y `/restablecer` | Recuperar la contraseña con un código de 6 números por correo |
| `POST /v1/contrasena/cambiar` | Cambiar la contraseña |
| `/admin/v1/...` | Panel administrativo (con `ADMIN_TOKEN`): negocios, plan, pago, prueba, suspender, módulos extra, equipos, contraseña temporal, respaldo, precios, actividad |

## Probarlo en una computadora

```bash
./gradlew :server:installDist
cd server && cp .env.ejemplo .env   # y llena las llaves (openssl rand -base64 32)
docker compose up -d --build
curl http://localhost:8080/salud
```

## Pruebas

`./gradlew :server:test` necesita un PostgreSQL (en GitHub lo pone el flujo). Localmente usa
`TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/vitrina_test?user=vitrina&password=vitrina`.

## Pendiente para la 2.0d (puesta en marcha)

- Droplet + volumen + PostgreSQL administrado en DigitalOcean, DNS de `api.facturaprord.online` y HTTPS.
- Publicar la imagen desde GitHub y copiar los respaldos nocturnos a Spaces.
