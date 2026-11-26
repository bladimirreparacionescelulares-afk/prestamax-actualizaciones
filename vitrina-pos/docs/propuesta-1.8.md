# Propuesta 1.8 en adelante (pendiente de aprobación)

Página con maquetas: https://claude.ai/artifact/NTypjnSXUqADVhmW31EJQi

## 1. Fallas de reglas del negocio (auditoría del código, 26/11/2026)

Confirmadas en el código:
- Se vende con la caja cerrada (el cobro y el principal no la revisan). → Exigir caja abierta en el equipo; aviso con "Abrir caja y cobrar"; también abonos y entregas del taller. Interruptor por negocio.
- Se vende más que el stock (solo un aviso). → Tope en el "+" y en la cantidad; el principal vuelve a validar. Permiso/opción "vender sin stock" apagado.
- Cantidades negativas aceptadas (entrada negativa resta stock; ajuste puede dejar stock negativo). → Solo positivas; ajuste no baja de 0; salidas como merma/devolución con motivo.
- Cualquiera cambia el precio en el carrito, incluso a 0 o bajo costo. → Permiso "Cambiar precios al vender"; aviso bajo costo; precio 0 solo como cortesía con motivo.
- Descuento sin límite (hasta 100 %). → Permiso "Dar descuentos" con tope (10 %); más pide PIN de administrador.
- PIN sin límite de intentos y con hash simple. → Bloqueo tras 5 intentos (1/5/15 min), PBKDF2 con sal.

Riesgos:
- Dos cajas venden la última unidad (sobre todo sin conexión). → Principal rechaza en línea; sin conexión se marca "vendida sin stock".
- Caja sin entradas/salidas/gastos ni devoluciones en efectivo. → Movimientos de caja y cierre por forma de pago.
- Anular ventas viejas sin límite. → Anular el mismo día; después, devolución.
- Sin devoluciones parciales. → Devolución por artículo con E34 parcial.
- Precio y venta en 0 permitidos. → Validar.
- El costo se reemplaza en cada entrada. → Costo promedio ponderado.
- Permisos solo en pantallas. → El principal valida el permiso de cada operación.
- Código de barras repetido solo validado en el formulario. → Validar en el principal.

## 2. SaaS
- PostgreSQL administrado (copias diarias, recuperación a un minuto), todos los negocios en una base con separación por negocio (row-level security).
- Servidor Kotlin (Ktor) reutilizando el motor actual (operaciones, sincronización, e-CF).
- Empezar en DigitalOcean (App Platform + Managed PostgreSQL + Spaces), región EE. UU. Este; alternativa Google Cloud (Cloud Run + Cloud SQL) o AWS (RDS).
- La app sigue local (SQLite) para trabajar sin internet; la nube hace de principal en internet.
- Certificados cifrados con KMS para los servicios e-CF en la nube.
- Falta: registro de negocios y planes, inicio de sesión de la cuenta, sincronización HTTPS con token por equipo, panel de administración, cobro de suscripción, monitoreo.

## 3. e-CF, fases restantes
- 6.4 Servicios en la nube por negocio: autenticación, recepción (ARECF firmado), aprobación comercial (ACECF).
- 6.5 Asistente del set de pruebas de certificación.
- 6.6 Devolución parcial (E34), nota de débito E33, ANECF de sobrantes, aprobación comercial, contingencia técnica, PDF A4, avisos de vencimiento.
- 6.7 Compras: facturas recibidas → entradas con NCF; E41 y E43 opcionales.

## 4. Módulo Inventario
Panel (valor a costo y venta, agotados, bajo mínimo, sin movimiento, más vendidos y margen), kardex con costo promedio, compras (órdenes, recepción con NCF, sugerencia), conteo físico con escáner y ajuste en bloque, mermas y devoluciones, ubicaciones/puestos con transferencias, IMEI/serie por unidad y variantes.

## Orden sugerido
1.8 reglas → 1.9 inventario → 2.0 nube → 2.1 e-CF 6.4–6.7.

## Preguntas abiertas
1. ¿Algún negocio vende sin caja? 2. ¿Vender sin stock nunca o con permiso? 3. % de descuento sin autorización (propuesto 10 %).
4. "Puesto 2", "Báez", "Cedecom": ¿ubicaciones o proveedores? 5. ¿Teléfonos con IMEI? 6. ¿DigitalOcean, Google Cloud o AWS?
