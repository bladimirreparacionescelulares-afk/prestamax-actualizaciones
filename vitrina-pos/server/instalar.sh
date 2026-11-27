#!/usr/bin/env bash
# Instalador del servidor de Vitrina POS en un Droplet con Ubuntu 24.04.
# Se pega en la consola del Droplet (como root):
#   curl -fsSL <dirección de este archivo> | bash
# Se puede volver a ejecutar: actualiza el servidor y conserva las llaves y los datos.
set -euo pipefail

DOMINIO="${DOMINIO:-api.facturaprord.online}"
REPO="bladimirreparacionescelulares-afk/prestamax-actualizaciones"
DESCARGA="https://github.com/$REPO/releases/download/vitrina-servidor"
ENVF=/etc/vitrina/vitrina.env
DATOS=/var/lib/vitrina
APP=/opt/vitrina

paso() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
[ "$(id -u)" = 0 ] || { echo "Ejecuta esto como root"; exit 1; }
export DEBIAN_FRONTEND=noninteractive

paso "1/7 Programas del sistema (Java, PostgreSQL, Caddy, cortafuegos)"
apt-get update -q
apt-get install -y -q openjdk-17-jre-headless postgresql caddy ufw fail2ban curl openssl unattended-upgrades
systemctl enable --now postgresql fail2ban

paso "2/7 Memoria de apoyo (swap de 2 GB)"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q /swapfile /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

paso "3/7 Cortafuegos: solo SSH, HTTP y HTTPS"
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

paso "4/7 Llaves y base de datos (se crean aquí y no salen del servidor)"
id vitrina >/dev/null 2>&1 || useradd --system --home "$APP" --shell /usr/sbin/nologin vitrina
mkdir -p /etc/vitrina "$DATOS" "$APP"
chown vitrina:vitrina "$DATOS"
chmod 700 "$DATOS"
NUEVO=0
if [ ! -f "$ENVF" ]; then
  NUEVO=1
  DBPASS=$(openssl rand -hex 24)
  cat > "$ENVF" <<EOF
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/vitrina?user=vitrina&password=$DBPASS
DB_PASSWORD=$DBPASS
DATA_DIR=$DATOS
HOST=127.0.0.1
PORT=8080
TOKEN_SECRET=$(openssl rand -base64 32)
MASTER_KEY=$(openssl rand -base64 32)
ADMIN_TOKEN=$(openssl rand -hex 32)
TRIAL_PLAN=pro
TRIAL_DAYS=14
RESEND_API_KEY=
MAIL_FROM=Vitrina POS <no-responder@facturaprord.online>
EOF
fi
chown root:vitrina "$ENVF"
chmod 640 "$ENVF"
DBPASS=$(sed -n 's/^DB_PASSWORD=//p' "$ENVF")
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='vitrina'" | grep -q 1; then
  sudo -u postgres psql -qc "CREATE ROLE vitrina LOGIN PASSWORD '$DBPASS'"
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='vitrina'" | grep -q 1; then
  sudo -u postgres createdb -O vitrina vitrina
fi

paso "5/7 Descargando el servidor de Vitrina POS"
TMP=$(mktemp -d)
curl -fsSL -o "$TMP/vitrina-server.tar" "$DESCARGA/vitrina-server.tar"
curl -fsSL -o "$TMP/vitrina-server.tar.sha256" "$DESCARGA/vitrina-server.tar.sha256"
echo "$(cat "$TMP/vitrina-server.tar.sha256")  $TMP/vitrina-server.tar" | sha256sum -c --quiet
rm -rf "$APP/nuevo" && mkdir -p "$APP/nuevo"
tar -xf "$TMP/vitrina-server.tar" -C "$APP/nuevo"
rm -rf "$APP/anterior"
[ -d "$APP/actual" ] && mv "$APP/actual" "$APP/anterior"
mv "$APP/nuevo/vitrina-server" "$APP/actual"
rm -rf "$APP/nuevo" "$TMP"

paso "6/7 Servicio (arranca solo al prender el servidor)"
cat > /etc/systemd/system/vitrina.service <<EOF
[Unit]
Description=Servidor de Vitrina POS
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service

[Service]
User=vitrina
EnvironmentFile=$ENVF
Environment=JAVA_OPTS=-Xmx768m
ExecStart=$APP/actual/bin/vitrina-server
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$DATOS
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable vitrina >/dev/null
systemctl restart vitrina

paso "7/7 HTTPS con candado para $DOMINIO"
cat > /etc/caddy/Caddyfile <<EOF
$DOMINIO {
    encode gzip
    reverse_proxy 127.0.0.1:8080
}
EOF
systemctl enable caddy >/dev/null
systemctl restart caddy

# Comando para actualizar después: "vitrina-actualizar"
cat > /usr/local/bin/vitrina-actualizar <<EOF
#!/usr/bin/env bash
curl -fsSL https://raw.githubusercontent.com/$REPO/claude/android-product-catalog-app-oef20t/vitrina-pos/server/instalar.sh | bash
EOF
chmod +x /usr/local/bin/vitrina-actualizar

printf 'Esperando a que arranque'
for i in $(seq 1 60); do
  if curl -fs http://127.0.0.1:8080/salud >/dev/null; then break; fi
  printf '.'; sleep 2
done
echo
if curl -fs http://127.0.0.1:8080/salud; then
  echo
  printf '\n\033[1;32mListo: el servidor de Vitrina POS está funcionando.\033[0m\n'
else
  printf '\n\033[1;31mEl servidor no arrancó. Mira el motivo con: journalctl -u vitrina -n 50\033[0m\n'
  exit 1
fi
if [ "$NUEVO" = 1 ]; then
  cat <<EOF

================================================================
 Clave del panel administrativo (guárdala en un lugar seguro,
 NO la compartas con nadie; sirve para manejar planes y pagos):

   $(sed -n 's/^ADMIN_TOKEN=//p' "$ENVF")

 Siempre la puedes volver a ver con:  grep ADMIN_TOKEN $ENVF
================================================================
EOF
fi
echo "Cuando el dominio apunte aquí, abre https://$DOMINIO/salud en el navegador."
