package com.pictech;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * 数据库驱动的多模块 Spring Boot 项目生成器 Mojo.
 * <p>
 * 使用命令示例:
 * mvn com.pictech:multi-module-generator:generate \
 *   -DgroupId=com.example.project \
 *   -DartifactId=my-awesome-project \
 *   -DdbUrl=jdbc:mysql://localhost:3306/your_database \
 *   -DdbUser=root \
 *   -DdbPassword=your_password
 */
@Mojo(name = "generate", requiresProject = false)
public class MultiModuleGeneratorMojo extends AbstractMojo {

    //<editor-fold desc="Maven插件参数">
    @Parameter(property = "groupId", required = true)
    private String groupId;

    @Parameter(property = "artifactId", required = true)
    private String artifactId;

    @Parameter(property = "version", defaultValue = "1.0.0-SNAPSHOT")
    private String version;

    @Parameter(property = "packageName")
    private String packageName;

    @Parameter(property = "projectDir", defaultValue = ".")
    private String projectDir;

    // --- 新增的数据库连接参数 ---
    @Parameter(property = "dbUrl", required = true)
    private String dbUrl;

    @Parameter(property = "dbUser", required = true)
    private String dbUser;

    @Parameter(property = "dbPassword", required = true)
    private String dbPassword;

    @Parameter(property = "dbDriver", defaultValue = "com.mysql.cj.jdbc.Driver")
    private String dbDriver;
    //</editor-fold>

    private final String[] modules = {"common", "dao", "service", "web"};

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // 1. 初始化和结构创建
            initialize();
            Path rootPath = createProjectDirectories();
            generateParentPom(rootPath);
            for (String module : modules) {
                generateModule(rootPath, module);
            }

            // 2. 数据库元数据驱动的代码生成
            generateCodeFromDatabase(rootPath);

            // 3. 生成中心化配置文件
            generateCentralizedConfig(rootPath);

            getLog().info("========================================================================");
            getLog().info("多模块项目 [" + artifactId + "] 已成功生成!");
            getLog().info("项目路径: " + rootPath.toAbsolutePath());
            getLog().info("========================================================================");

        } catch (Exception e) {
            getLog().error("生成项目时发生错误", e);
            throw new MojoExecutionException("生成项目结构时出错", e);
        }
    }

    //<editor-fold desc="核心流程方法">
    private void initialize() {
        if (packageName == null || packageName.isEmpty()) {
            packageName = groupId;
        }
        getLog().info("开始生成多模块项目: " + artifactId);
        getLog().info("包名: " + packageName);
    }

    private Path createProjectDirectories() throws IOException {
        Path rootPath = Paths.get(projectDir, artifactId);
        if (Files.exists(rootPath)) {
            getLog().warn("项目目录已存在，将覆盖部分文件: " + rootPath);
        } else {
            Files.createDirectories(rootPath);
        }
        return rootPath;
    }

    private void generateCodeFromDatabase(Path rootPath) throws SQLException, IOException {
        getLog().info("正在连接数据库以生成代码: " + dbUrl);
        List<TableInfo> tableInfos = getDatabaseMetadata();
        getLog().info("发现 " + tableInfos.size() + " 个表，将为它们生成CRUD代码。");

        for (TableInfo table : tableInfos) {
            getLog().info("  -> 正在处理表: " + table.getTableName());
            // 生成DAO层 (Entity, Mapper, XML)
            generateEntity(rootPath, table);
            generateMapperInterface(rootPath, table);
            generateMapperXml(rootPath, table);
            // 生成Service层
            generateService(rootPath, table);
            // 生成Web层
            generateController(rootPath, table);
        }
    }
    //</editor-fold>

    //<editor-fold desc="数据库元数据获取">
    private List<TableInfo> getDatabaseMetadata() throws SQLException {
        List<TableInfo> tableInfos = new ArrayList<>();
        Connection conn = null;
        try {
            Class.forName(dbDriver);
            Properties props = new Properties();
            props.setProperty("user", dbUser);
            props.setProperty("password", dbPassword);
            // 允许获取表注释
            props.setProperty("useInformationSchema", "true");
            conn = DriverManager.getConnection(dbUrl, props);
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();

            ResultSet tablesResultSet = metaData.getTables(catalog, null, "%", new String[]{"TABLE"});
            while (tablesResultSet.next()) {
                String tableName = tablesResultSet.getString("TABLE_NAME");
                String remarks = tablesResultSet.getString("REMARKS"); // 获取表注释
                TableInfo tableInfo = new TableInfo(tableName, remarks);

                // 获取列信息
                ResultSet columnsResultSet = metaData.getColumns(catalog, null, tableName, "%");
                while (columnsResultSet.next()) {
                    String columnName = columnsResultSet.getString("COLUMN_NAME");
                    int dataType = columnsResultSet.getInt("DATA_TYPE");
                    String columnRemarks = columnsResultSet.getString("REMARKS");
                    tableInfo.addColumn(columnName, dataType, columnRemarks);
                }
                columnsResultSet.close();

                // 获取主键信息
                ResultSet pkResultSet = metaData.getPrimaryKeys(catalog, null, tableName);
                if (pkResultSet.next()) {
                    tableInfo.setPrimaryKey(pkResultSet.getString("COLUMN_NAME"));
                }
                pkResultSet.close();

                tableInfos.add(tableInfo);
            }
            tablesResultSet.close();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到数据库驱动: " + dbDriver, e);
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
        return tableInfos;
    }
    //</editor-fold>

    //<editor-fold desc="代码生成逻辑 (Entity, Mapper, Service, Controller)">
    private void generateEntity(Path rootPath, TableInfo table) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path entityPath = rootPath.resolve("dao/src/main/java/" + packagePath + "/dao/entity");
        Files.createDirectories(entityPath);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".dao.entity;\n\n");
        sb.append("import lombok.Data;\n");
        sb.append("import java.io.Serializable;\n");
        // 动态导入所需类型
        boolean hasDate = table.getColumns().stream().anyMatch(c -> c.getJavaType().equals("Date"));
        boolean hasBigDecimal = table.getColumns().stream().anyMatch(c -> c.getJavaType().equals("java.math.BigDecimal"));
        boolean hasLocalDateTime = table.getColumns().stream().anyMatch(c -> c.getJavaType().equals("java.time.LocalDateTime"));
        if (hasDate) sb.append("import java.util.Date;\n");
        if (hasBigDecimal) sb.append("import java.math.BigDecimal;\n");
        if (hasLocalDateTime) sb.append("import java.time.LocalDateTime;\n");
        sb.append("\n");

        if (table.getTableComment() != null && !table.getTableComment().isEmpty()) {
            sb.append("/**\n * ").append(table.getTableComment()).append("\n */\n");
        }
        sb.append("@Data\n");
        sb.append("public class ").append(table.getEntityName()).append(" implements Serializable {\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");

        for (ColumnInfo column : table.getColumns()) {
            if (column.getComment() != null && !column.getComment().isEmpty()) {
                sb.append("    /**\n     * ").append(column.getComment()).append("\n     */\n");
            }
            sb.append("    private ").append(column.getJavaType()).append(" ").append(column.getJavaName()).append(";\n\n");
        }
        sb.append("}\n");

        writeFile(entityPath.resolve(table.getEntityName() + ".java"), sb.toString());
    }

    private void generateMapperInterface(Path rootPath, TableInfo table) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path mapperPath = rootPath.resolve("dao/src/main/java/" + packagePath + "/dao/mapper");
        Files.createDirectories(mapperPath);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".dao.mapper;\n\n");
        sb.append("import ").append(packageName).append(".dao.entity.").append(table.getEntityName()).append(";\n");
        sb.append("import org.apache.ibatis.annotations.Mapper;\n");
        sb.append("import org.apache.ibatis.annotations.Param;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/**\n * ").append(table.getTableComment()).append(" - 数据访问接口\n */\n");
        sb.append("@Mapper\n");
        sb.append("public interface ").append(table.getEntityName()).append("Mapper {\n\n");

        // insert
        sb.append("    /**\n     * 选择性插入一条记录\n     * @param record 实体对象\n     * @return 影响行数\n     */\n");
        sb.append("    int insertSelective(").append(table.getEntityName()).append(" record);\n\n");

        // delete
        sb.append("    /**\n     * 根据主键删除记录\n     * @param id 主键\n     * @return 影响行数\n     */\n");
        sb.append("    int deleteById(@Param(\"id\") ").append(table.getPkJavaType()).append(" id);\n\n");

        // update
        sb.append("    /**\n     * 根据主键选择性更新记录\n     * @param record 实体对象\n     * @return 影响行数\n     */\n");
        sb.append("    int updateByIdSelective(").append(table.getEntityName()).append(" record);\n\n");

        // select by id
        sb.append("    /**\n     * 根据主键查询记录\n     * @param id 主键\n     * @return 实体对象，或null\n     */\n");
        sb.append("    ").append(table.getEntityName()).append(" findById(@Param(\"id\") ").append(table.getPkJavaType()).append(" id);\n\n");

        // select list
        sb.append("    /**\n     * 查询列表，按ID倒序排序\n     * @param offset 偏移量\n     * @param limit  记录数\n     * @return 实体对象列表\n     */\n");
        sb.append("    List<").append(table.getEntityName()).append("> findList(@Param(\"offset\") int offset, @Param(\"limit\") int limit);\n\n");

        sb.append("}\n");
        writeFile(mapperPath.resolve(table.getEntityName() + "Mapper.java"), sb.toString());
    }

    private void generateMapperXml(Path rootPath, TableInfo table) throws IOException {
        Path mapperXmlPath = rootPath.resolve("dao/src/main/resources/mapper");
        Files.createDirectories(mapperXmlPath);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n");
        sb.append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n");
        sb.append("<mapper namespace=\"").append(packageName).append(".dao.mapper.").append(table.getEntityName()).append("Mapper\">\n\n");

        // ResultMap
        sb.append("    <resultMap id=\"BaseResultMap\" type=\"").append(packageName).append(".dao.entity.").append(table.getEntityName()).append("\">\n");
        for (ColumnInfo column : table.getColumns()) {
            if (column.getName().equals(table.getPrimaryKeyName())) {
                sb.append("        <id column=\"").append(column.getName()).append("\" property=\"").append(column.getJavaName()).append("\" />\n");
            } else {
                sb.append("        <result column=\"").append(column.getName()).append("\" property=\"").append(column.getJavaName()).append("\" />\n");
            }
        }
        sb.append("    </resultMap>\n\n");

        // Base Column List
        sb.append("    <sql id=\"Base_Column_List\">\n        ");
        for (int i = 0; i < table.getColumns().size(); i++) {
            sb.append(table.getColumns().get(i).getName());
            if (i < table.getColumns().size() - 1) sb.append(", ");
        }
        sb.append("\n    </sql>\n\n");

        // insertSelective
        sb.append("    <!-- 选择性插入 -->\n");
        sb.append("    <insert id=\"insertSelective\" parameterType=\"").append(packageName).append(".dao.entity.").append(table.getEntityName()).append("\"");
        if(table.getPkJavaType().equals("Long") || table.getPkJavaType().equals("Integer")){
            sb.append(" useGeneratedKeys=\"true\" keyProperty=\"").append(table.getPkJavaName()).append("\"");
        }
        sb.append(">\n");
        sb.append("        INSERT INTO ").append(table.getTableName()).append("\n");
        sb.append("        <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
        for (ColumnInfo column : table.getColumns()) {
            sb.append("            <if test=\"").append(column.getJavaName()).append(" != null\">").append(column.getName()).append(",</if>\n");
        }
        sb.append("        </trim>\n");
        sb.append("        <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">\n");
        for (ColumnInfo column : table.getColumns()) {
            sb.append("            <if test=\"").append(column.getJavaName()).append(" != null\">#{").append(column.getJavaName()).append("},</if>\n");
        }
        sb.append("        </trim>\n");
        sb.append("    </insert>\n\n");

        // deleteById
        sb.append("    <!-- 根据主键删除 -->\n");
        sb.append("    <delete id=\"deleteById\">\n");
        sb.append("        DELETE FROM ").append(table.getTableName()).append(" WHERE ").append(table.getPrimaryKeyName()).append(" = #{id}\n");
        sb.append("    </delete>\n\n");

        // updateByIdSelective
        sb.append("    <!-- 根据主键选择性更新 -->\n");
        sb.append("    <update id=\"updateByIdSelective\" parameterType=\"").append(packageName).append(".dao.entity.").append(table.getEntityName()).append("\">\n");
        sb.append("        UPDATE ").append(table.getTableName()).append("\n");
        sb.append("        <set>\n");
        for (ColumnInfo column : table.getColumns()) {
            if (!column.getName().equals(table.getPrimaryKeyName())) { // 主键不应被更新
                sb.append("            <if test=\"").append(column.getJavaName()).append(" != null\">").append(column.getName()).append(" = #{").append(column.getJavaName()).append("},</if>\n");
            }
        }
        sb.append("        </set>\n");
        sb.append("        WHERE ").append(table.getPrimaryKeyName()).append(" = #{").append(table.getPkJavaName()).append("}\n");
        sb.append("    </update>\n\n");

        // findById
        sb.append("    <!-- 根据主键查询 -->\n");
        sb.append("    <select id=\"findById\" resultMap=\"BaseResultMap\">\n");
        sb.append("        SELECT <include refid=\"Base_Column_List\" />\n");
        sb.append("        FROM ").append(table.getTableName()).append(" WHERE ").append(table.getPrimaryKeyName()).append(" = #{id}\n");
        sb.append("    </select>\n\n");

        // findList
        sb.append("    <!-- 分页查询，ID倒序 -->\n");
        sb.append("    <select id=\"findList\" resultMap=\"BaseResultMap\">\n");
        sb.append("        SELECT <include refid=\"Base_Column_List\" />\n");
        sb.append("        FROM ").append(table.getTableName()).append(" ORDER BY ").append(table.getPrimaryKeyName()).append(" DESC\n");
        sb.append("        LIMIT #{offset}, #{limit}\n");
        sb.append("    </select>\n\n");

        sb.append("</mapper>\n");
        writeFile(mapperXmlPath.resolve(table.getEntityName() + "Mapper.xml"), sb.toString());
    }

    private void generateService(Path rootPath, TableInfo table) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path servicePath = rootPath.resolve("service/src/main/java/" + packagePath + "/service");
        Files.createDirectories(servicePath);
        Path serviceImplPath = servicePath.resolve("impl");
        Files.createDirectories(serviceImplPath);

        // Service 接口
        StringBuilder interfaceSb = new StringBuilder();
        interfaceSb.append("package ").append(packageName).append(".service;\n\n");
        interfaceSb.append("import ").append(packageName).append(".dao.entity.").append(table.getEntityName()).append(";\n");
        interfaceSb.append("import java.util.List;\n\n");
        interfaceSb.append("/**\n * ").append(table.getTableComment()).append(" - 服务接口\n */\n");
        interfaceSb.append("public interface ").append(table.getEntityName()).append("Service {\n\n");
        interfaceSb.append("    boolean create(").append(table.getEntityName()).append(" record);\n\n");
        interfaceSb.append("    boolean removeById(").append(table.getPkJavaType()).append(" id);\n\n");
        interfaceSb.append("    boolean updateById(").append(table.getEntityName()).append(" record);\n\n");
        interfaceSb.append("    ").append(table.getEntityName()).append(" getById(").append(table.getPkJavaType()).append(" id);\n\n");
        interfaceSb.append("    List<").append(table.getEntityName()).append("> getList(int pageNum, int pageSize);\n\n");
        interfaceSb.append("}\n");
        writeFile(servicePath.resolve(table.getEntityName() + "Service.java"), interfaceSb.toString());

        // Service 实现
        StringBuilder implSb = new StringBuilder();
        implSb.append("package ").append(packageName).append(".service.impl;\n\n");
        implSb.append("import ").append(packageName).append(".dao.entity.").append(table.getEntityName()).append(";\n");
        implSb.append("import ").append(packageName).append(".dao.mapper.").append(table.getEntityName()).append("Mapper;\n");
        implSb.append("import ").append(packageName).append(".service.").append(table.getEntityName()).append("Service;\n");
        implSb.append("import org.springframework.beans.factory.annotation.Autowired;\n");
        implSb.append("import org.springframework.stereotype.Service;\n");
        implSb.append("import java.util.List;\n\n");
        implSb.append("/**\n * ").append(table.getTableComment()).append(" - 服务实现\n */\n");
        implSb.append("@Service\n");
        implSb.append("public class ").append(table.getEntityName()).append("ServiceImpl implements ").append(table.getEntityName()).append("Service {\n\n");
        implSb.append("    @Autowired\n");
        implSb.append("    private ").append(table.getEntityName()).append("Mapper ").append(table.getInstanceName()).append("Mapper;\n\n");

        implSb.append("    @Override\n");
        implSb.append("    public boolean create(").append(table.getEntityName()).append(" record) {\n");
        implSb.append("        return ").append(table.getInstanceName()).append("Mapper.insertSelective(record) > 0;\n");
        implSb.append("    }\n\n");

        implSb.append("    @Override\n");
        implSb.append("    public boolean removeById(").append(table.getPkJavaType()).append(" id) {\n");
        implSb.append("        return ").append(table.getInstanceName()).append("Mapper.deleteById(id) > 0;\n");
        implSb.append("    }\n\n");

        implSb.append("    @Override\n");
        implSb.append("    public boolean updateById(").append(table.getEntityName()).append(" record) {\n");
        implSb.append("        return ").append(table.getInstanceName()).append("Mapper.updateByIdSelective(record) > 0;\n");
        implSb.append("    }\n\n");

        implSb.append("    @Override\n");
        implSb.append("    public ").append(table.getEntityName()).append(" getById(").append(table.getPkJavaType()).append(" id) {\n");
        implSb.append("        return ").append(table.getInstanceName()).append("Mapper.findById(id);\n");
        implSb.append("    }\n\n");

        implSb.append("    @Override\n");
        implSb.append("    public List<").append(table.getEntityName()).append("> getList(int pageNum, int pageSize) {\n");
        implSb.append("        int offset = (pageNum - 1) * pageSize;\n");
        implSb.append("        return ").append(table.getInstanceName()).append("Mapper.findList(offset, pageSize);\n");
        implSb.append("    }\n");

        implSb.append("}\n");
        writeFile(serviceImplPath.resolve(table.getEntityName() + "ServiceImpl.java"), implSb.toString());
    }

    private void generateController(Path rootPath, TableInfo table) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path controllerPath = rootPath.resolve("web/src/main/java/" + packagePath + "/web/controller");
        Files.createDirectories(controllerPath);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".web.controller;\n\n");
        sb.append("import ").append(packageName).append(".common.Result;\n");
        sb.append("import ").append(packageName).append(".dao.entity.").append(table.getEntityName()).append(";\n");
        sb.append("import ").append(packageName).append(".service.").append(table.getEntityName()).append("Service;\n");
        sb.append("import org.springframework.beans.factory.annotation.Autowired;\n");
        sb.append("import org.springframework.web.bind.annotation.*;\n");
        sb.append("import java.util.List;\n\n");

        sb.append("/**\n * ").append(table.getTableComment()).append(" - API 接口\n */\n");
        sb.append("@RestController\n");
        sb.append("@RequestMapping(\"/api/").append(table.getRequestMapping()).append("\")\n");
        sb.append("public class ").append(table.getEntityName()).append("Controller {\n\n");
        sb.append("    @Autowired\n");
        sb.append("    private ").append(table.getEntityName()).append("Service ").append(table.getInstanceName()).append("Service;\n\n");

        // Create
        sb.append("    @PostMapping\n");
        sb.append("    public Result<Boolean> create(@RequestBody ").append(table.getEntityName()).append(" record) {\n");
        sb.append("        return Result.success(").append(table.getInstanceName()).append("Service.create(record));\n");
        sb.append("    }\n\n");

        // Delete
        sb.append("    @DeleteMapping(\"/{id}\")\n");
        sb.append("    public Result<Boolean> delete(@PathVariable(\"id\") ").append(table.getPkJavaType()).append(" id) {\n");
        sb.append("        return Result.success(").append(table.getInstanceName()).append("Service.removeById(id));\n");
        sb.append("    }\n\n");

        // Update
        sb.append("    @PutMapping\n");
        sb.append("    public Result<Boolean> update(@RequestBody ").append(table.getEntityName()).append(" record) {\n");
        sb.append("        return Result.success(").append(table.getInstanceName()).append("Service.updateById(record));\n");
        sb.append("    }\n\n");

        // Get by ID
        sb.append("    @GetMapping(\"/{id}\")\n");
        sb.append("    public Result<").append(table.getEntityName()).append("> getById(@PathVariable(\"id\") ").append(table.getPkJavaType()).append(" id) {\n");
        sb.append("        ").append(table.getEntityName()).append(" entity = ").append(table.getInstanceName()).append("Service.getById(id);\n");
        sb.append("        return Result.success(entity);\n");
        sb.append("    }\n\n");

        // Get List
        sb.append("    @GetMapping(\"/list\")\n");
        sb.append("    public Result<List<").append(table.getEntityName()).append(">> getList(@RequestParam(defaultValue = \"1\") int pageNum, \n");
        sb.append("                                                       @RequestParam(defaultValue = \"10\") int pageSize) {\n");
        sb.append("        List<").append(table.getEntityName()).append("> list = ").append(table.getInstanceName()).append("Service.getList(pageNum, pageSize);\n");
        sb.append("        return Result.success(list);\n");
        sb.append("    }\n");

        sb.append("}\n");
        writeFile(controllerPath.resolve(table.getEntityName() + "Controller.java"), sb.toString());
    }
    //</editor-fold>

    //<editor-fold desc="POM和配置文件生成">
    private void generateParentPom(Path rootPath) throws IOException {
        String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>" + groupId + "</groupId>\n" +
                "    <artifactId>" + artifactId + "</artifactId>\n" +
                "    <version>" + version + "</version>\n" +
                "    <packaging>pom</packaging>\n" +
                "    <name>" + artifactId + "</name>\n\n" +
                "    <properties>\n" +
                "        <java.version>1.8</java.version>\n" +
                "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n" +
                "        <spring.boot.version>2.7.12</spring.boot.version>\n" +
                "        <mybatis.spring.boot.version>2.3.0</mybatis.spring.boot.version>\n" +
                "        <mysql.connector.version>8.0.33</mysql.connector.version>\n" +
                "        <druid.version>1.2.16</druid.version>\n" +
                "        <lombok.version>1.18.28</lombok.version>\n" +
                "    </properties>\n\n" +
                "    <modules>\n" +
                "        <module>common</module>\n" +
                "        <module>dao</module>\n" +
                "        <module>service</module>\n" +
                "        <module>web</module>\n" +
                "    </modules>\n\n" +
                "    <dependencyManagement>\n" +
                "        <dependencies>\n" +
                "            <dependency>\n" +
                "                <groupId>org.springframework.boot</groupId>\n" +
                "                <artifactId>spring-boot-dependencies</artifactId>\n" +
                "                <version>${spring.boot.version}</version>\n" +
                "                <type>pom</type>\n" +
                "                <scope>import</scope>\n" +
                "            </dependency>\n" +
                "            <dependency>\n" +
                "                <groupId>" + groupId + "</groupId>\n" +
                "                <artifactId>" + artifactId + "-common</artifactId>\n" +
                "                <version>${project.version}</version>\n" +
                "            </dependency>\n" +
                "            <dependency>\n" +
                "                <groupId>" + groupId + "</groupId>\n" +
                "                <artifactId>" + artifactId + "-dao</artifactId>\n" +
                "                <version>${project.version}</version>\n" +
                "            </dependency>\n" +
                "            <dependency>\n" +
                "                <groupId>" + groupId + "</groupId>\n" +
                "                <artifactId>" + artifactId + "-service</artifactId>\n" +
                "                <version>${project.version}</version>\n" +
                "            </dependency>\n" +
                "        </dependencies>\n" +
                "    </dependencyManagement>\n\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.projectlombok</groupId>\n" +
                "            <artifactId>lombok</artifactId>\n" +
                "            <version>${lombok.version}</version>\n" +
                "            <scope>provided</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n\n" +
                "    <build>\n" +
                "        <pluginManagement>\n" +
                "            <plugins>\n" +
                "                <plugin>\n" +
                "                    <groupId>org.springframework.boot</groupId>\n" +
                "                    <artifactId>spring-boot-maven-plugin</artifactId>\n" +
                "                    <version>${spring.boot.version}</version>\n" +
                "                </plugin>\n" +
                "            </plugins>\n" +
                "        </pluginManagement>\n" +
                "    </build>\n" +
                "</project>";
        writeFile(rootPath.resolve("pom.xml"), pomContent);
    }

    private void generateModule(Path rootPath, String moduleName) throws IOException {
        Path modulePath = rootPath.resolve(moduleName);
        Files.createDirectories(modulePath);

        createMavenDirectories(modulePath);
        generateModulePom(modulePath, moduleName);

        // 为common和web模块生成初始代码
        if ("common".equals(moduleName)) {
            generateCommonResultClass(modulePath);
        } else if ("web".equals(moduleName)) {
            generateMainApplicationClass(modulePath);
        }
    }

    private void createMavenDirectories(Path modulePath) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Files.createDirectories(modulePath.resolve("src/main/java/" + packagePath));
        Files.createDirectories(modulePath.resolve("src/main/resources"));
        Files.createDirectories(modulePath.resolve("src/test/java/" + packagePath));
    }

    private void generateModulePom(Path modulePath, String moduleName) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n");
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(groupId).append("</groupId>\n");
        pom.append("        <artifactId>").append(artifactId).append("</artifactId>\n");
        pom.append("        <version>").append(version).append("</version>\n");
        pom.append("    </parent>\n");
        pom.append("    <artifactId>").append(artifactId).append("-").append(moduleName).append("</artifactId>\n\n");

        pom.append("    <dependencies>\n");
        addModuleSpecificDependencies(pom, moduleName);
        pom.append("    </dependencies>\n\n");

        if ("web".equals(moduleName)) {
            pom.append("    <build>\n");
            pom.append("        <plugins>\n");
            pom.append("            <plugin>\n");
            pom.append("                <groupId>org.springframework.boot</groupId>\n");
            pom.append("                <artifactId>spring-boot-maven-plugin</artifactId>\n");
            pom.append("            </plugin>\n");
            pom.append("        </plugins>\n");
            pom.append("    </build>\n");
        }

        pom.append("</project>");
        writeFile(modulePath.resolve("pom.xml"), pom.toString());
    }

    private void addModuleSpecificDependencies(StringBuilder pom, String moduleName) {
        switch (moduleName) {
            case "dao":
                pom.append("        <dependency>\n")
                        .append("            <groupId>").append(groupId).append("</groupId>\n")
                        .append("            <artifactId>").append(artifactId).append("-common</artifactId>\n")
                        .append("        </dependency>\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>org.mybatis.spring.boot</groupId>\n")
                        .append("            <artifactId>mybatis-spring-boot-starter</artifactId>\n")
                        .append("            <version>${mybatis.spring.boot.version}</version>\n")
                        .append("        </dependency>\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>mysql</groupId>\n")
                        .append("            <artifactId>mysql-connector-java</artifactId>\n")
                        .append("            <version>${mysql.connector.version}</version>\n")
                        .append("        </dependency>\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>com.alibaba</groupId>\n")
                        .append("            <artifactId>druid-spring-boot-starter</artifactId>\n")
                        .append("            <version>${druid.version}</version>\n")
                        .append("        </dependency>\n");
                break;
            case "service":
                pom.append("        <dependency>\n")
                        .append("            <groupId>").append(groupId).append("</groupId>\n")
                        .append("            <artifactId>").append(artifactId).append("-dao</artifactId>\n")
                        .append("        </dependency>\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>org.springframework.boot</groupId>\n")
                        .append("            <artifactId>spring-boot-starter</artifactId>\n")
                        .append("        </dependency>\n");
                break;
            case "web":
                pom.append("        <dependency>\n")
                        .append("            <groupId>").append(groupId).append("</groupId>\n")
                        .append("            <artifactId>").append(artifactId).append("-service</artifactId>\n")
                        .append("        </dependency>\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>org.springframework.boot</groupId>\n")
                        .append("            <artifactId>spring-boot-starter-web</artifactId>\n")
                        .append("        </dependency>\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>org.springframework.boot</groupId>\n")
                        .append("            <artifactId>spring-boot-starter-test</artifactId>\n")
                        .append("            <scope>test</scope>\n")
                        .append("        </dependency>\n");
                break;
            default: // common or others
                break;
        }
    }

    private void generateCommonResultClass(Path modulePath) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path commonPath = modulePath.resolve("src/main/java/" + packagePath + "/common");
        Files.createDirectories(commonPath);
        String resultClassContent = "package " + packageName + ".common;\n\n" +
                "import lombok.Data;\n" +
                "import java.io.Serializable;\n\n" +
                "@Data\n" +
                "public class Result<T> implements Serializable {\n" +
                "    private int code;\n" +
                "    private String message;\n" +
                "    private T data;\n\n" +
                "    private Result(int code, String message, T data) {\n" +
                "        this.code = code;\n" +
                "        this.message = message;\n" +
                "        this.data = data;\n" +
                "    }\n\n" +
                "    public static <T> Result<T> success(T data) {\n" +
                "        return new Result<>(200, \"Success\", data);\n" +
                "    }\n\n" +
                "    public static Result<Void> success() {\n" +
                "        return new Result<>(200, \"Success\", null);\n" +
                "    }\n\n" +
                "    public static <T> Result<T> error(int code, String message) {\n" +
                "        return new Result<>(code, message, null);\n" +
                "    }\n" +
                "}\n";
        writeFile(commonPath.resolve("Result.java"), resultClassContent);
    }

    private void generateMainApplicationClass(Path modulePath) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path webPath = modulePath.resolve("src/main/java/" + packagePath + "/web");
        Files.createDirectories(webPath);

        String appClassName = toPascalCase(artifactId.replace("-", " ")) + "Application";

        String appClassContent = "package " + packageName + ".web;\n\n" +
                "import org.mybatis.spring.annotation.MapperScan;\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication(scanBasePackages = \"" + packageName + "\")\n" +
                "@MapperScan(\"" + packageName + ".dao.mapper\")\n" +
                "public class " + appClassName + " {\n" +
                "    public static void main(String[] args) {\n" +
                "        SpringApplication.run(" + appClassName + ".class, args);\n" +
                "    }\n" +
                "}\n";
        writeFile(webPath.resolve(appClassName + ".java"), appClassContent);
    }

    private void generateCentralizedConfig(Path rootPath) throws IOException {
        Path webResourcesPath = rootPath.resolve("web/src/main/resources");
        Files.createDirectories(webResourcesPath);

        StringBuilder yml = new StringBuilder();
        yml.append("# 服务器配置\n");
        yml.append("server:\n");
        yml.append("  port: 8080\n\n");
        yml.append("# Spring 应用配置\n");
        yml.append("spring:\n");
        yml.append("  application:\n");
        yml.append("    name: ").append(artifactId).append("\n\n");
        yml.append("  # 数据库连接池配置\n");
        yml.append("  datasource:\n");
        yml.append("    type: com.alibaba.druid.pool.DruidDataSource\n");
        yml.append("    driver-class-name: ").append(dbDriver).append("\n");
        yml.append("    url: ").append(dbUrl).append("\n");
        yml.append("    username: ").append(dbUser).append("\n");
        yml.append("    password: ").append(dbPassword).append("\n\n");
        yml.append("# MyBatis 配置\n");
        yml.append("mybatis:\n");
        yml.append("  # 自动扫描Mapper XML文件\n");
        yml.append("  mapper-locations: classpath:mapper/*.xml\n");
        yml.append("  # 自动将数据库下划线命名映射为驼峰命名\n");
        yml.append("  configuration:\n");
        yml.append("    map-underscore-to-camel-case: true\n\n");
        yml.append("# 日志级别配置\n");
        yml.append("logging:\n");
        yml.append("  level:\n");
        yml.append("    ").append(packageName).append(": debug\n");
        yml.append("    org.springframework: warn\n");

        writeFile(webResourcesPath.resolve("application.yml"), yml.toString());
    }
    //</editor-fold>

    //<editor-fold desc="工具类方法和内部类">
    private void writeFile(Path filePath, String content) throws IOException {
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(content);
        }
    }

    private static String toCamelCase(String s) {
        String[] parts = s.split("_");
        String camelCaseString = "";
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                camelCaseString = part.toLowerCase();
            } else {
                camelCaseString = camelCaseString + toPascalCase(part);
            }
        }
        return camelCaseString;
    }

    private static String toPascalCase(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] parts = s.split("_|\\s+|-");
        StringBuilder pascalCaseString = new StringBuilder();
        for (String part : parts) {
            if(part.isEmpty()) continue;
            pascalCaseString.append(part.substring(0, 1).toUpperCase()).append(part.substring(1).toLowerCase());
        }
        return pascalCaseString.toString();
    }

    private static String mapSqlTypeToJavaType(int sqlType) {
        switch (sqlType) {
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
//            case Types.TEXT:
                return "String";
            case Types.INTEGER:
            case Types.TINYINT:
            case Types.SMALLINT:
                return "Integer";
            case Types.BIGINT:
                return "Long";
            case Types.DOUBLE:
                return "Double";
            case Types.FLOAT:
            case Types.REAL:
                return "Float";
            case Types.DECIMAL:
            case Types.NUMERIC:
                return "java.math.BigDecimal";
            case Types.DATE:
                return "java.time.LocalDate";
            case Types.TIME:
                return "java.time.LocalTime";
            case Types.TIMESTAMP:
                return "java.time.LocalDateTime";
            case Types.BIT:
            case Types.BOOLEAN:
                return "Boolean";
            default:
                return "Object";
        }
    }

    // --- 内部数据持有类 ---
    private static class TableInfo {
        private final String tableName;
        private final String tableComment;
        private final String entityName;
        private final String instanceName;
        private final String requestMapping;
        private final List<ColumnInfo> columns = new ArrayList<>();
        private String primaryKeyName;
        private String pkJavaType;
        private String pkJavaName;

        public TableInfo(String tableName, String tableComment) {
            this.tableName = tableName;
            this.tableComment = tableComment != null ? tableComment.trim() : "";
            this.entityName = toPascalCase(tableName);
            this.instanceName = toCamelCase(tableName);
            // 将 "user_info" 转换为 "user-info"
            this.requestMapping = tableName.toLowerCase().replace('_', '-');
        }

        public void addColumn(String name, int type, String comment) {
            this.columns.add(new ColumnInfo(name, type, comment));
        }

        public void setPrimaryKey(String pkName) {
            this.primaryKeyName = pkName;
            this.columns.stream()
                    .filter(c -> c.getName().equals(pkName))
                    .findFirst()
                    .ifPresent(pkCol -> {
                        this.pkJavaType = pkCol.getJavaType();
                        this.pkJavaName = pkCol.getJavaName();
                    });
        }

        // Getters
        public String getTableName() { return tableName; }
        public String getTableComment() { return tableComment; }
        public String getEntityName() { return entityName; }
        public String getInstanceName() { return instanceName; }
        public String getRequestMapping() { return requestMapping; }
        public List<ColumnInfo> getColumns() { return columns; }
        public String getPrimaryKeyName() { return primaryKeyName; }
        public String getPkJavaType() { return pkJavaType; }
        public String getPkJavaName() { return pkJavaName; }
    }

    private static class ColumnInfo {
        private final String name;
        private final String javaName;
        private final String javaType;
        private final String comment;

        public ColumnInfo(String name, int type, String comment) {
            this.name = name;
            this.javaName = toCamelCase(name);
            this.javaType = mapSqlTypeToJavaType(type);
            this.comment = comment != null ? comment.trim() : "";
        }

        // Getters
        public String getName() { return name; }
        public String getJavaName() { return javaName; }
        public String getJavaType() { return javaType; }
        public String getComment() { return comment; }
    }
    //</editor-fold>
}


