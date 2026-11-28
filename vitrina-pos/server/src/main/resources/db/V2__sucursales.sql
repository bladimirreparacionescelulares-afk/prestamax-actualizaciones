-- 2.2: sucursales. Cada equipo está en una sucursal del negocio (la 1 es la Principal).
ALTER TABLE equipo ADD COLUMN sucursal_id BIGINT NOT NULL DEFAULT 1;
-- Cuántas sucursales permite cada plan (0 = sin límite).
ALTER TABLE plan ADD COLUMN max_sucursales INT NOT NULL DEFAULT 1;
UPDATE plan SET max_sucursales = 2 WHERE id = 'negocio';
UPDATE plan SET max_sucursales = 0 WHERE id = 'pro';
