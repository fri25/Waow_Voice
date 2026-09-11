#!/usr/bin/env bash
# Installe et lance le backend Assistant Fon sur une instance Ubuntu (EC2).
# Usage : bash install.sh
# Secret optionnel : export DEEPSEEK_API_KEY=... avant de lancer.
set -euo pipefail

REPO="${REPO:-https://huggingface.co/datasets/octavebahoun/assistant-fon-backend}"
DOSSIER="${DOSSIER:-/opt/assistantfon}"
PORT="${PORT:-7860}"

echo "==> Installation de Docker (si absent)"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
fi

echo "==> Recuperation du code depuis $REPO"
if [ -d "$DOSSIER/.git" ]; then
  git -C "$DOSSIER" pull --ff-only || true
else
  rm -rf "$DOSSIER"
  git clone --depth 1 "$REPO" "$DOSSIER"
fi
cd "$DOSSIER"

echo "==> Construction de l'image (telecharge ~1,5 Go de modeles, patiente)"
docker build -t assistantfon:latest .

echo "==> Demarrage du conteneur sur le port $PORT"
docker rm -f assistantfon >/dev/null 2>&1 || true
docker run -d \
  --name assistantfon \
  --restart unless-stopped \
  -p "${PORT}:7860" \
  -e DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
  -e GROQ_API_KEY="${GROQ_API_KEY:-}" \
  assistantfon:latest

sleep 5
docker ps --filter name=assistantfon
echo
echo "==> Test :  curl http://localhost:${PORT}/sante"
echo "==> Logs :  docker logs -f assistantfon"
