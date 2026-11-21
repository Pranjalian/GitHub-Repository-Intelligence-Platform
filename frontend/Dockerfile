# syntax=docker/dockerfile:1
# Frontend image for running the app locally with Docker Compose (see compose.yaml).
# The static build is served by nginx, which proxies /api to the backend container.

# ---- Build stage ----
FROM node:22-alpine AS build
WORKDIR /src

COPY package.json pnpm-lock.yaml ./
# Install the exact pnpm version pinned in package.json ("packageManager").
RUN npm install -g "pnpm@$(node -p "require('./package.json').packageManager.split('@')[1]")"
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ---- Runtime stage ----
FROM nginxinc/nginx-unprivileged:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
