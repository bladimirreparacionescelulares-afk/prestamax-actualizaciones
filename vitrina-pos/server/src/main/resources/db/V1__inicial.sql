-- Vitrina POS · base de control (PostgreSQL): cuentas, negocios, planes, equipos y restablecimientos.
-- Los datos de cada negocio (ventas, inventario, caja…) viven en su propia base, fuera de aquí.

CREATE TABLE plan (
  id TEXT PRIMARY KEY,
  nombre TEXT NOT NULL,
  precio_mensual BIGINT NOT NULL DEFAULT 0,   -- centavos
  precio_anual BIGINT NOT NULL DEFAULT 0,
  max_equipos INT NOT NULL,
  max_usuarios INT NOT NULL,                  -- 0 = sin límite
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  orden INT NOT NULL DEFAULT 0
);

CREATE TABLE plan_modulo (
  plan_id TEXT NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
  modulo TEXT NOT NULL,
  PRIMARY KEY (plan_id, modulo)
);

CREATE TABLE cuenta (
  id BIGSERIAL PRIMARY KEY,
  correo TEXT NOT NULL UNIQUE,
  hash_contrasena TEXT NOT NULL,
  nombre TEXT NOT NULL DEFAULT '',
  debe_cambiar BOOLEAN NOT NULL DEFAULT FALSE,
  intentos_fallidos INT NOT NULL DEFAULT 0,
  bloqueada_hasta TIMESTAMPTZ,
  creada TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE negocio (
  id BIGSERIAL PRIMARY KEY,
  cuenta_id BIGINT NOT NULL REFERENCES cuenta(id),
  nombre TEXT NOT NULL,
  tipo TEXT NOT NULL DEFAULT '',
  suspendido BOOLEAN NOT NULL DEFAULT FALSE,
  creado TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX negocio_cuenta ON negocio(cuenta_id);

CREATE TABLE suscripcion (
  negocio_id BIGINT PRIMARY KEY REFERENCES negocio(id),
  plan_id TEXT NOT NULL REFERENCES plan(id),
  prueba_hasta TIMESTAMPTZ,
  pagado_hasta TIMESTAMPTZ,
  periodo TEXT NOT NULL DEFAULT 'mensual'
);

CREATE TABLE modulo_extra (
  negocio_id BIGINT NOT NULL REFERENCES negocio(id),
  modulo TEXT NOT NULL,
  precio_mensual BIGINT NOT NULL DEFAULT 0,
  desde TIMESTAMPTZ NOT NULL DEFAULT now(),
  hasta TIMESTAMPTZ,
  PRIMARY KEY (negocio_id, modulo)
);

CREATE TABLE equipo (
  id TEXT PRIMARY KEY,
  negocio_id BIGINT NOT NULL REFERENCES negocio(id),
  nombre TEXT NOT NULL,
  plataforma TEXT NOT NULL DEFAULT '',
  secreto_hash TEXT NOT NULL,
  creado TIMESTAMPTZ NOT NULL DEFAULT now(),
  ultimo_uso TIMESTAMPTZ,
  revocado BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX equipo_negocio ON equipo(negocio_id);

-- Códigos para recuperar la contraseña (por correo) y contraseñas temporales que da el administrador.
CREATE TABLE restablecimiento (
  id BIGSERIAL PRIMARY KEY,
  cuenta_id BIGINT NOT NULL REFERENCES cuenta(id),
  codigo_hash TEXT NOT NULL,
  vence TIMESTAMPTZ NOT NULL,
  intentos INT NOT NULL DEFAULT 0,
  usado BOOLEAN NOT NULL DEFAULT FALSE,
  creado TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX restablecimiento_cuenta ON restablecimiento(cuenta_id);

-- Lo que hace el dueño de Vitrina POS desde el panel administrativo.
CREATE TABLE admin_actividad (
  id BIGSERIAL PRIMARY KEY,
  accion TEXT NOT NULL,
  detalle TEXT NOT NULL DEFAULT '',
  creado TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Planes iniciales (precios por definir: se cambian desde el panel sin tocar código).
INSERT INTO plan (id, nombre, max_equipos, max_usuarios, orden) VALUES
  ('esencial', 'Esencial', 1, 2, 1),
  ('negocio', 'Negocio', 3, 6, 2),
  ('pro', 'Pro', 10, 0, 3);

INSERT INTO plan_modulo (plan_id, modulo) VALUES
  ('esencial', 'ventas'), ('esencial', 'etiquetas'),
  ('negocio', 'ventas'), ('negocio', 'etiquetas'), ('negocio', 'inventario'), ('negocio', 'cotizaciones'),
  ('negocio', 'variantes'), ('negocio', 'actividad'), ('negocio', 'multiequipo'),
  ('pro', 'ventas'), ('pro', 'etiquetas'), ('pro', 'inventario'), ('pro', 'cotizaciones'), ('pro', 'variantes'),
  ('pro', 'actividad'), ('pro', 'multiequipo'), ('pro', 'imei'), ('pro', 'taller'), ('pro', 'facturacion');
