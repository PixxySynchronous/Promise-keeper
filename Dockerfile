# Promise Keeper Voice: web app + API in one image.   docker compose up --build
# Stage 1 builds the React app; stage 2 is the Python server that serves it. No secrets are baked in:
# .dockerignore keeps every .env file out of the build, and speech-to-text defaults to the offline mock.

FROM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json web/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
# Same folder layout as the repository, because the code finds its neighbours by relative path:
# detect.py reads the Android app's phrase list, and config.py looks for ../web/dist.
WORKDIR /srv/voice-backend
COPY voice-backend/requirements.txt ./
RUN pip install -r requirements.txt
COPY voice-backend/app ./app
COPY voice-backend/static ./static
COPY app/src/main/java/com/satyam/whatsappbot/domain/CommitmentPhrases.kt /srv/app/src/main/java/com/satyam/whatsappbot/domain/CommitmentPhrases.kt
COPY --from=web /src/web/dist /srv/web/dist

# Run as an ordinary user. data/ (the SQLite file) and demo_audio/ are the only places it writes.
RUN useradd --uid 1000 --create-home pk && mkdir -p data demo_audio && chown pk:pk data demo_audio
USER pk

# Safe defaults: offline mock, live calls off. Real Gnani calls need all three set on purpose at run time.
ENV STT_PROVIDER=mock GNANI_LIVE_CALLS_ENABLED=false
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status == 200 else 1)"
# Seeding is safe to repeat: it keeps an existing demo user and rewrites the same demo audio.
CMD ["sh", "-c", "python -m app.admin seed && exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
