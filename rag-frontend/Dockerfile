FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
# npm install: lockfile의 플랫폼별 optional 바이너리를 컨테이너 OS에 맞게 재해결
# (npm ci는 macOS lockfile의 darwin-arm64 바이너리를 linux-arm64-musl 환경에서 찾지 못함)
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
