# Mainapi for the local Sokoul backend (API/docker-compose.yml). Settings come from the environment, never from a .env
# baked into the image.
FROM node:22-bookworm-slim
WORKDIR /app
# better-sqlite3 builds from source when no prebuilt binary matches.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY . .
ENV NODE_ENV=production
EXPOSE 25565
CMD ["node", "server.js"]
