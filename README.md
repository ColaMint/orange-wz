## OrzRepacker

### Makefile 命令

| 命令 | 说明 |
|------|------|
| `make dev` | 同时启动后端 + 前端开发服务器 |
| `make dev-backend` | 仅启动后端 (Spring Boot) |
| `make dev-frontend` | 仅启动前端 (Vite dev server) |
| `make build` | 构建前端 + 后端，生成发行包 ZIP |
| `make build-frontend` | 仅构建前端（输出到 static 目录） |
| `make build-backend` | 仅构建后端（会先构建前端） |
| `make run` | 运行打包后的应用 |
| `make clean` | 清理构建产物 |

### I18n 多语言配置方法
创建 config.ini 文件
```ini
# zh_CN / en_US
language = zh_CN
```

### 打包流程
1. 更新 application.properties 里新版本的 key
2. 提交所有记录
3. 执行 UpdateVersionNumber 更新版本号
4. 将新版本的 key 提交到更新服务器
5. 执行 `make build` 进行打包
6. 修改 Luncher 的版本号（最新版本号在 application.properties 里）
7. 重新编译 Luncher
8. 将 Luncher 放入解压后的目录中，执行 Luncher 将 Jar 加密成 data.bin