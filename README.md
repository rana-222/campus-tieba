# 校园贴吧 MVC 系统

## 技术栈
- 表现层：HTML / CSS / JavaScript，位于 `src/main/resources/static`
- 控制层：Spring MVC REST Controller
- 业务层：Service
- 数据访问层：Spring JDBC Repository
- 数据库：MySQL `campus_tieba`（首次启动自动创建数据库、数据表并初始化数据）

## 运行
1. 安装 JDK 21、Maven 和 MySQL，并启动 MySQL 服务。
2. 按需设置 `DB_USERNAME`、`DB_PASSWORD`，默认账号为 `root`、默认密码为空。
3. 在项目根目录运行 `mvn spring-boot:run`，访问 `http://localhost:8080`。

演示账号：`student / Student123!`；管理员：`admin / Admin123!`。

## 发给他人前

接收方需要安装 JDK 21、Maven 和 MySQL。首次运行会自动创建 `campus_tieba` 数据库、数据表并写入演示数据，所有帖子、回复、点赞、收藏、浏览记录均保存在 MySQL 中。

启动命令：

```powershell
mvn spring-boot:run
```

浏览器访问 `http://localhost:8080`。打包源码时不需要发送 `target` 文件夹。
