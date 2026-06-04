.PHONY: dev dev-frontend dev-backend build build-frontend build-backend clean run package

# 开发：同时启动后端和前端
dev:
	@make -j2 dev-backend dev-frontend

dev-backend:
	./mvnw spring-boot:run

dev-frontend:
	cd vue && yarn dev

# 构建
build: build-frontend build-backend

build-frontend:
	cd vue && yarn install && yarn build-only

build-backend: build-frontend
	./mvnw clean package -DskipTests

# 运行打包后的应用
run:
	java -javaagent:target/OrzRepacker.jar -jar target/OrzRepacker.jar

# 完整打包（发行包）
package: build-backend

# 清理
clean:
	./mvnw clean
	rm -rf vue/dist
