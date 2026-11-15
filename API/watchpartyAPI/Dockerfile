# Watch party server (Socket.IO) for the local Sokoul backend.
FROM node:22-bookworm-slim
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY watchparty.js ./
EXPOSE 25566
CMD ["node", "watchparty.js"]
