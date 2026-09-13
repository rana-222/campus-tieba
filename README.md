# 校园贴吧 MVC 系统

## 技术栈
- 表现层：HTML / CSS / JavaScript，位于 `src/main/resources/static`
- 控制层：Spring MVC REST Controller
- 业务层：Service
- 数据访问层：Spring JDBC Repository
- 数据库：MySQL `campus_tieba`（首次启动自动创建数据库、数据表并初始化数据）

## 运行

### 方式一：桌面启动器（推荐）
1. 安装 JDK、Maven、MySQL 与 .NET 8 桌面运行时，并启动 MySQL 服务。
2. 双击项目根目录的 `Tieba.exe`：自动清理 8080 端口 → 启动后端 → 就绪后在内嵌浏览器窗口中打开应用，关闭窗口即退出后端。
3. 启动器源码位于 `launcher/`（C# WinForms + WebView2），可用 `dotnet publish` 重新构建。

### 方式二：命令行
1. 按需设置 `DB_USERNAME`、`DB_PASSWORD`。
2. 在项目根目录运行 `mvn spring-boot:run`，访问 `http://localhost:8080`。

演示账号：`student / Student123!`；管理员：`admin / Admin123!`。

## 发给他人前

接收方需要安装 JDK 21、Maven 和 MySQL。首次运行会自动创建 `campus_tieba` 数据库、数据表并写入演示数据，所有帖子、回复、点赞、收藏、浏览记录均保存在 MySQL 中。

启动命令：

```powershell
mvn spring-boot:run
```

浏览器访问 `http://localhost:8080`。打包源码时不需要发送 `target` 文件夹。
