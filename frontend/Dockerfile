# Frontend image (ADR-0023): Next.js standalone server. Build from the repository root:
#   docker build -f frontend/Dockerfile -t operator-frontend .
FROM node:22-bookworm-slim AS build
RUN corepack enable
WORKDIR /src/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend ./
COPY contracts /src/contracts
RUN pnpm build

FROM node:22-bookworm-slim
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
WORKDIR /app
COPY --from=build --chown=node:node /src/frontend/.next/standalone ./
COPY --from=build --chown=node:node /src/frontend/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node", "server.js"]
