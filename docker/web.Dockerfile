FROM node:24.21.0-alpine AS build
WORKDIR /work
COPY package.json package-lock.json ./
COPY frontend/package.json frontend/package.json
COPY tests/e2e/package.json tests/e2e/package.json
COPY backend/java/package.json backend/java/package.json
COPY backend/python/package.json backend/python/package.json
COPY backend/rust/package.json backend/rust/package.json
COPY tools/fixtures/package.json tools/fixtures/package.json
RUN npm ci
COPY frontend ./frontend
RUN npm run build --workspace frontend
FROM nginxinc/nginx-unprivileged:1.29.3-alpine
USER root
RUN apk add --no-cache curl
USER nginx
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /work/frontend/dist /usr/share/nginx/html
EXPOSE 8080
