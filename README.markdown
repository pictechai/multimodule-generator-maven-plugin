# 数据库驱动的多模块 Spring Boot 项目生成器

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Language](https://img.shields.io/badge/Language-Java-blue.svg)]()
[![Framework](https://img.shields.io/badge/Framework-Maven_Plugin-red.svg)]()

这是一个高效的 Maven 插件，旨在通过“数据库优先”的模式，一键生成结构清晰、代码完备的多模块 Spring Boot 项目。您只需预先设计好数据库表结构，插件便会自动为您创建包括 `common`, `dao`, `service`, `web` 在内的标准分层模块，并生成所有表的 `Entity`, `Mapper`, `Service`, `Controller` 基础 CRUD 代码。

**告别繁琐的项目初始化和重复的 CRUD 代码编写，让您更专注于核心业务逻辑的实现。**

---

### ✨ 核心功能

*   **多模块结构**: 自动创建标准的 `common`, `dao`, `service`, `web` 四层模块结构，职责分明。
*   **数据库优先**: 根据现有的数据库表结构（表名、字段、主键、注释）自动生成代码。
*   **全链路代码生成**:
    *   **DAO 层**: 生成与表对应的 `Entity`（支持Lombok）、MyBatis 的 `Mapper` 接口和对应的 `Mapper.xml` 文件（包含基础的增删改查SQL）。
    *   **Service 层**: 生成 `Service` 接口和 `ServiceImpl` 实现类。
    *   **Web 层**: 生成 `Controller` 类，并提供符合 RESTful 风格的 CRUD API 接口。
*   **自动化配置**:
    *   自动生成父 `pom.xml` 和各子模块的 `pom.xml`，并处理好模块间的依赖关系。
    *   自动在 `web` 模块下生成 `application.yml` 文件，并预填好数据库连接信息。
*   **高度规范**:
    *   自动将数据库的 `snake_case` (下划线命名) 转换为 Java 的 `camelCase` (驼峰命名)。
    *   充分利用数据库表和字段的注释，生成到对应的 Java 类和字段文档中。

---

### 🚀 如何使用

#### 前置要求

1.  已安装 [Apache Maven](https://maven.apache.org/download.cgi)。
2.  拥有一个已设计好表结构的数据库（目前主要针对 MySQL 优化）。

#### 执行命令

在您希望创建项目的目录下（例如 `D:\workspace`），打开命令行工具，执行以下命令。请将参数替换为您自己的配置。

```bash
mvn com.pictech:multi-module-generator:generate \
  -DgroupId=com.example.company \
  -DartifactId=my-project \
  -DdbUrl=jdbc:mysql://localhost:3306/my_database?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC \
  -DdbUser=root \
  -DdbPassword=your_password
```

#### 参数说明

| 参数名        | 说明                                                                             | 是否必须 | 默认值                 |
| :------------ | :------------------------------------------------------------------------------- | :------- | :--------------------- |
| `groupId`     | 项目的 Group ID，通常是您的公司或组织的反向域名。                                | **是**   | -                      |
| `artifactId`  | 项目的 Artifact ID，即项目名称。                                                 | **是**   | -                      |
| `dbUrl`       | 目标数据库的 JDBC URL。                                                          | **是**   | -                      |
| `dbUser`      | 数据库用户名。                                                                   | **是**   | -                      |
| `dbPassword`  | 数据库密码。                                                                     | **是**   | -                      |
| `version`     | 项目版本号。                                                                     | 否       | `1.0.0-SNAPSHOT`       |
| `packageName` | Java 的根包名。                                                                  | 否       | 与 `groupId` 相同      |
| `projectDir`  | 项目生成的根目录。                                                               | 否       | `.` (当前目录)         |
| `dbDriver`    | 数据库驱动类。                                                                   | 否       | `com.mysql.cj.jdbc.Driver` |

---

### 📁 生成结果

执行成功后，您将得到如下标准的多模块项目结构：

```
my-project/
├── pom.xml                   # 父POM，管理所有依赖和模块
├── common/
│   ├── pom.xml
│   └── src/main/java/com/example/company/common/
│       └── Result.java       # 通用返回结果封装类
├── dao/
│   ├── pom.xml
│   ├── src/main/java/com/example/company/dao/
│   │   ├── entity/
│   │   │   └── UserInfo.java # 数据库 user_info 表对应的实体类
│   │   └── mapper/
│   │       └── UserInfoMapper.java # MyBatis Mapper 接口
│   └── src/main/resources/
│       └── mapper/
│           └── UserInfoMapper.xml  # MyBatis SQL 映射文件
├── service/
│   ├── pom.xml
│   └── src/main/java/com/example/company/service/
│       ├── impl/
│       │   └── UserInfoServiceImpl.java # 服务实现类
│       └── UserInfoService.java     # 服务接口
└── web/
    ├── pom.xml
    ├── src/main/java/com/example/company/web/
    │   ├── controller/
    │   │   └── UserInfoController.java # RESTful API 控制器
    │   └── MyProjectApplication.java # Spring Boot 启动类
    └── src/main/resources/
        └── application.yml     # 中心化配置文件
```

---

### 🔧 定制与扩展

该插件的核心逻辑均在 `MultiModuleGeneratorMojo.java` 中。您可以根据需要轻松进行二次开发：

*   **修改代码模板**: 直接在 `generateEntity`, `generateController` 等方法中修改 `StringBuilder` 拼接的字符串，以适应您的代码风格或团队规范。
*   **支持其他数据库**: 在 `mapSqlTypeToJavaType` 方法中添加对其他数据库类型（如 Oracle, PostgreSQL）的映射。
*   **增加/修改依赖**: 在 `generateParentPom` 和 `addModuleSpecificDependencies` 方法中调整生成的 `pom.xml` 文件内容。
*   **集成模板引擎**: 为了更优雅地管理代码模板，可以将 `StringBuilder` 替换为 [FreeMarker](https://freemarker.apache.org/) 或 [Velocity](https://velocity.apache.org/) 等模板引擎。

---

### 📄 许可

本项目采用 [MIT](https://opensource.org/licenses/MIT) 许可。