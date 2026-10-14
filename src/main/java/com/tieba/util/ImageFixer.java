package com.tieba.util;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 交互式图片引用修复工具。
 *
 * 用法：
 *   java -cp "target/classes;<mysql-connector-j.jar>" com.tieba.util.ImageFixer
 *
 * 流程：
 *   1. 扫描 posts.image_url / users.avatar / users.background_url / boards.icon
 *      中指向不存在文件的图片引用；
 *   2. 逐条展示，列出上传目录中可用的候选图片；
 *   3. 使用者输入编号选择替换图片，或输入 s 跳过；
 *   4. 全部选择完成后统一确认并执行更新。
 */
public class ImageFixer {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/campus_tieba?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "ASDqweasd12";
    private static final Path UPLOAD_DIR = Path.of("E:\\tieba-data");
    private static final String URL_PREFIX = "/uploads/";

    /** 待修复的一条记录 */
    private static class FixItem {
        final String table;
        final String column;
        final long id;
        final String oldUrl;
        String chosenFile; // null 表示跳过

        FixItem(String table, String column, long id, String oldUrl) {
            this.table = table;
            this.column = column;
            this.id = id;
            this.oldUrl = oldUrl;
        }
    }

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(UPLOAD_DIR)) {
            System.err.println("上传目录不存在: " + UPLOAD_DIR.toAbsolutePath());
            System.exit(1);
        }

        // 加载上传目录中所有可用图片（不分类型，全部展示给用户选）
        List<String> allFiles = loadImageFiles();
        if (allFiles.isEmpty()) {
            System.err.println("上传目录中没有可用图片，无法修复。");
            System.exit(1);
        }

        System.out.println("=== 可用图片列表（共 " + allFiles.size() + " 张）===");
        for (int i = 0; i < allFiles.size(); i++) {
            System.out.printf("  [%d] %s%n", i, allFiles.get(i));
        }
        System.out.println();

        // 收集所有缺失引用
        List<FixItem> items = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            items.addAll(scanMissing(conn, "posts", "image_url"));
            items.addAll(scanMissing(conn, "users", "avatar"));
            items.addAll(scanMissing(conn, "users", "background_url"));
            items.addAll(scanMissing(conn, "boards", "icon"));
        }

        if (items.isEmpty()) {
            System.out.println("没有缺失的图片引用，无需修复。");
            return;
        }

        System.out.println("=== 发现 " + items.size() + " 条缺失的图片引用 ===");
        System.out.println("对每条记录，输入候选编号选择替换图片，输入 s 跳过，输入 q 退出。");
        System.out.println();

        Scanner sc = new Scanner(System.in, "UTF-8");
        for (FixItem item : items) {
            System.out.println("--------------------------------------------------");
            System.out.printf("表: %s | 字段: %s | id: %d%n", item.table, item.column, item.id);
            System.out.println("原引用: " + item.oldUrl);
            System.out.print("请选择替换图片编号 (0-" + (allFiles.size() - 1) + ") 或 s 跳过: ");

            String line = sc.nextLine().trim();
            if ("q".equalsIgnoreCase(line)) {
                System.out.println("已退出，未做任何修改。");
                return;
            }
            if ("s".equalsIgnoreCase(line)) {
                System.out.println("已跳过。");
                continue;
            }
            try {
                int idx = Integer.parseInt(line);
                if (idx < 0 || idx >= allFiles.size()) {
                    System.out.println("编号超出范围，已跳过。");
                    continue;
                }
                item.chosenFile = allFiles.get(idx);
                System.out.println("已选择: " + item.chosenFile);
            } catch (NumberFormatException e) {
                System.out.println("输入无效，已跳过。");
            }
        }

        // 统计将执行的更新
        List<FixItem> toUpdate = items.stream().filter(i -> i.chosenFile != null).toList();
        if (toUpdate.isEmpty()) {
            System.out.println("\n没有选择任何替换，未修改数据库。");
            return;
        }

        System.out.println("\n=== 待执行更新（" + toUpdate.size() + " 条）===");
        for (FixItem item : toUpdate) {
            System.out.printf("  %s.%s id=%d: %s -> %s%n",
                    item.table, item.column, item.id,
                    item.oldUrl, URL_PREFIX + item.chosenFile);
        }

        System.out.print("\n确认执行？(y/N): ");
        String confirm = sc.nextLine().trim();
        if (!"y".equalsIgnoreCase(confirm) && !"yes".equalsIgnoreCase(confirm)) {
            System.out.println("已取消，未修改数据库。");
            return;
        }

        // 执行更新
        int done = 0;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            for (FixItem item : toUpdate) {
                String newUrl = URL_PREFIX + item.chosenFile;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + item.table + " SET " + item.column + " = ? WHERE id = ?")) {
                    ps.setString(1, newUrl);
                    ps.setLong(2, item.id);
                    done += ps.executeUpdate();
                }
            }
        }

        System.out.println("\n=== 完成，成功更新 " + done + " 条记录 ===");
    }

    /** 加载上传目录中的所有图片文件名。 */
    private static List<String> loadImageFiles() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** 扫描指定表列，返回引用了不存在文件的记录列表。 */
    private static List<FixItem> scanMissing(Connection conn, String table, String column) throws SQLException {
        List<FixItem> result = new ArrayList<>();
        String sql = "SELECT id, " + column + " FROM " + table
                + " WHERE " + column + " IS NOT NULL AND " + column + " LIKE '/uploads/%'";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String url = rs.getString(column);
                String fname = url.substring(URL_PREFIX.length());
                int q = fname.indexOf('?');
                if (q > 0) fname = fname.substring(0, q);
                if (!Files.exists(UPLOAD_DIR.resolve(fname))) {
                    result.add(new FixItem(table, column, rs.getLong("id"), url));
                }
            }
        }
        return result;
    }
}
